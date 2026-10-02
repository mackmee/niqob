-- ==============================================================================
-- NIQOB CLOUD MIGRATION: REAL SCREEN TIME MANAGEMENT (PHASE 1)
-- Table: public.screen_time_limits
-- RPCs: public.set_screen_time_limit, public.get_screen_time_limit
-- Production Hardened, RLS-Enforced, Fully Idempotent
-- ==============================================================================

-- 1. Create screen_time_limits table
CREATE TABLE IF NOT EXISTS public.screen_time_limits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    daily_limit_minutes INT NOT NULL DEFAULT 120 CHECK (daily_limit_minutes >= 0 AND daily_limit_minutes <= 1440),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_screen_time_limits_device UNIQUE (device_id)
);

-- 2. Indexes for performance
CREATE INDEX IF NOT EXISTS idx_screen_time_limits_family_id ON public.screen_time_limits(family_id);
CREATE INDEX IF NOT EXISTS idx_screen_time_limits_device_id ON public.screen_time_limits(device_id);

-- 3. Automatic timestamp trigger
DROP TRIGGER IF EXISTS trigger_screen_time_limits_updated_at ON public.screen_time_limits;
CREATE TRIGGER trigger_screen_time_limits_updated_at
    BEFORE UPDATE ON public.screen_time_limits
    FOR EACH ROW EXECUTE FUNCTION public.set_current_timestamp_updated_at();

-- 4. Enable Row Level Security (RLS)
ALTER TABLE public.screen_time_limits ENABLE ROW LEVEL SECURITY;

-- 5. RLS Policies: Strict Family Isolation for Parents
DROP POLICY IF EXISTS "Parents can view screen time limits in their family" ON public.screen_time_limits;
CREATE POLICY "Parents can view screen time limits in their family"
    ON public.screen_time_limits
    FOR SELECT
    USING (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Parents can insert screen time limits in their family" ON public.screen_time_limits;
CREATE POLICY "Parents can insert screen time limits in their family"
    ON public.screen_time_limits
    FOR INSERT
    WITH CHECK (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Parents can update screen time limits in their family" ON public.screen_time_limits;
CREATE POLICY "Parents can update screen time limits in their family"
    ON public.screen_time_limits
    FOR UPDATE
    USING (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Parents can delete screen time limits in their family" ON public.screen_time_limits;
CREATE POLICY "Parents can delete screen time limits in their family"
    ON public.screen_time_limits
    FOR DELETE
    USING (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

-- 6. Secure RPC: set_screen_time_limit
-- Authenticated parents only; validates family ownership; upserts atomically.
CREATE OR REPLACE FUNCTION public.set_screen_time_limit(
    p_device_id UUID,
    p_daily_limit_minutes INT,
    p_enabled BOOLEAN DEFAULT true
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_limit RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- Authorization check
    IF v_caller_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Authentication required. Please sign in as a parent.'
        );
    END IF;

    -- Parameter validation
    IF p_device_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID cannot be null.'
        );
    END IF;

    IF p_daily_limit_minutes IS NULL OR p_daily_limit_minutes < 0 OR p_daily_limit_minutes > 1440 THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_LIMIT',
            'message', 'Daily limit must be between 0 and 1440 minutes (24 hours).'
        );
    END IF;

    -- Verify device exists and belongs to parent's family
    SELECT d.id, d.family_id, f.parent_id
    INTO v_device
    FROM public.devices d
    JOIN public.families f ON f.id = d.family_id
    WHERE d.id = p_device_id;

    IF v_device IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'DEVICE_NOT_FOUND',
            'message', 'Device record not found.'
        );
    END IF;

    IF v_device.parent_id != v_caller_uid THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'You do not have permission to manage screen time for this device.'
        );
    END IF;

    -- Atomic upsert
    INSERT INTO public.screen_time_limits (
        family_id,
        device_id,
        daily_limit_minutes,
        enabled,
        created_at,
        updated_at
    ) VALUES (
        v_device.family_id,
        p_device_id,
        p_daily_limit_minutes,
        COALESCE(p_enabled, true),
        v_now,
        v_now
    )
    ON CONFLICT (device_id) DO UPDATE
    SET daily_limit_minutes = EXCLUDED.daily_limit_minutes,
        enabled = EXCLUDED.enabled,
        updated_at = v_now
    RETURNING * INTO v_limit;

    RETURN jsonb_build_object(
        'success', true,
        'limit', jsonb_build_object(
            'id', v_limit.id,
            'family_id', v_limit.family_id,
            'device_id', v_limit.device_id,
            'daily_limit_minutes', v_limit.daily_limit_minutes,
            'enabled', v_limit.enabled,
            'created_at', v_limit.created_at,
            'updated_at', v_limit.updated_at
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 7. Secure RPC: get_screen_time_limit
-- Authenticated parents or verified paired child devices can read limit.
CREATE OR REPLACE FUNCTION public.get_screen_time_limit(
    p_device_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_limit RECORD;
BEGIN
    IF p_device_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID cannot be null.'
        );
    END IF;

    -- Verify device exists
    SELECT d.id, d.family_id, f.parent_id
    INTO v_device
    FROM public.devices d
    JOIN public.families f ON f.id = d.family_id
    WHERE d.id = p_device_id;

    IF v_device IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'DEVICE_NOT_FOUND',
            'message', 'Device record not found.'
        );
    END IF;

    -- Authorization check
    IF v_caller_uid IS NOT NULL THEN
        IF v_device.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'You do not have permission to view screen time for this device.'
            );
        END IF;
    ELSE
        -- Unauthenticated caller: Must be actively paired child device in this family
        IF NOT EXISTS (
            SELECT 1
            FROM public.pairing_sessions ps
            WHERE ps.child_device_id = p_device_id::text
              AND ps.family_id = v_device.family_id
              AND ps.status = 'CONNECTED'
        ) THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Access denied: device is not an active connected child device.'
            );
        END IF;
    END IF;

    -- Query current limit
    SELECT * INTO v_limit
    FROM public.screen_time_limits
    WHERE device_id = p_device_id;

    IF v_limit IS NULL THEN
        RETURN jsonb_build_object(
            'success', true,
            'is_default', true,
            'limit', jsonb_build_object(
                'family_id', v_device.family_id,
                'device_id', p_device_id,
                'daily_limit_minutes', 120,
                'enabled', true,
                'created_at', NOW(),
                'updated_at', NOW()
            )
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'is_default', false,
        'limit', jsonb_build_object(
            'id', v_limit.id,
            'family_id', v_limit.family_id,
            'device_id', v_limit.device_id,
            'daily_limit_minutes', v_limit.daily_limit_minutes,
            'enabled', v_limit.enabled,
            'created_at', v_limit.created_at,
            'updated_at', v_limit.updated_at
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 8. Execution Privileges (Principle of Least Privilege)
REVOKE EXECUTE ON FUNCTION public.set_screen_time_limit(UUID, INT, BOOLEAN) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_screen_time_limit(UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.set_screen_time_limit(UUID, INT, BOOLEAN) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_screen_time_limit(UUID) TO authenticated, anon;

-- 9. Table Level Permissions (Subject to RLS)
GRANT ALL ON TABLE public.screen_time_limits TO authenticated;
