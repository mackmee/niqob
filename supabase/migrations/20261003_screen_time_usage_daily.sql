-- ==============================================================================
-- NIQOB CLOUD MIGRATION: CLOUD SCREEN TIME USAGE SYNC (PHASE 3D)
-- Table: public.screen_time_usage_daily
-- RPCs: public.upsert_screen_time_usage_daily, public.get_screen_time_usage_daily
-- Production Hardened, RLS-Enforced, Fully Idempotent
-- ==============================================================================

-- 1. Create screen_time_usage_daily table
CREATE TABLE IF NOT EXISTS public.screen_time_usage_daily (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    total_usage_minutes INT NOT NULL CHECK (total_usage_minutes >= 0),
    daily_limit_minutes INT CHECK (daily_limit_minutes >= 0 AND daily_limit_minutes <= 1440),
    remaining_minutes INT NOT NULL DEFAULT 0 CHECK (remaining_minutes >= 0),
    usage_percentage INT NOT NULL DEFAULT 0 CHECK (usage_percentage >= 0),
    is_limit_reached BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_screen_time_usage_daily_device_date UNIQUE (device_id, usage_date)
);

-- 2. Indexes for high performance
CREATE INDEX IF NOT EXISTS idx_screen_time_usage_daily_family_id ON public.screen_time_usage_daily(family_id);
CREATE INDEX IF NOT EXISTS idx_screen_time_usage_daily_device_id ON public.screen_time_usage_daily(device_id);
CREATE INDEX IF NOT EXISTS idx_screen_time_usage_daily_date ON public.screen_time_usage_daily(usage_date);
CREATE INDEX IF NOT EXISTS idx_screen_time_usage_daily_device_date ON public.screen_time_usage_daily(device_id, usage_date);

-- 3. Automatic updated_at trigger
DROP TRIGGER IF EXISTS trigger_screen_time_usage_daily_updated_at ON public.screen_time_usage_daily;
CREATE TRIGGER trigger_screen_time_usage_daily_updated_at
    BEFORE UPDATE ON public.screen_time_usage_daily
    FOR EACH ROW EXECUTE FUNCTION public.set_current_timestamp_updated_at();

-- 4. Enable Row Level Security (RLS)
ALTER TABLE public.screen_time_usage_daily ENABLE ROW LEVEL SECURITY;

-- 5. RLS Policies: Strict Family Isolation for Parents
DROP POLICY IF EXISTS "Parents can view screen time usage in their family" ON public.screen_time_usage_daily;
CREATE POLICY "Parents can view screen time usage in their family"
    ON public.screen_time_usage_daily
    FOR SELECT
    USING (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Parents can manage screen time usage in their family" ON public.screen_time_usage_daily;
CREATE POLICY "Parents can manage screen time usage in their family"
    ON public.screen_time_usage_daily
    FOR ALL
    USING (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

-- 6. Secure RPC: upsert_screen_time_usage_daily
-- Child device writes today's summary atomically.
CREATE OR REPLACE FUNCTION public.upsert_screen_time_usage_daily(
    p_device_id UUID,
    p_usage_date DATE,
    p_total_usage_minutes INT,
    p_daily_limit_minutes INT DEFAULT NULL,
    p_remaining_minutes INT DEFAULT NULL,
    p_usage_percentage INT DEFAULT NULL,
    p_is_limit_reached BOOLEAN DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_limit RECORD;
    v_limit_minutes INT;
    v_limit_enabled BOOLEAN := true;
    v_remaining_minutes INT;
    v_usage_percentage INT;
    v_is_limit_reached BOOLEAN;
    v_usage RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- Parameter validation
    IF p_device_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID cannot be null.'
        );
    END IF;

    IF p_usage_date IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Usage date cannot be null.'
        );
    END IF;

    IF p_total_usage_minutes IS NULL OR p_total_usage_minutes < 0 THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_USAGE',
            'message', 'Total usage minutes must be non-negative.'
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

    -- Caller authorization
    IF v_caller_uid IS NOT NULL THEN
        -- Authenticated parent caller: verify ownership of family
        IF v_device.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'You do not have permission to sync usage for this device.'
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

    -- Resolve configured limit if not provided
    IF p_daily_limit_minutes IS NOT NULL THEN
        v_limit_minutes := GREATEST(0, LEAST(1440, p_daily_limit_minutes));
    ELSE
        SELECT daily_limit_minutes, enabled INTO v_limit
        FROM public.screen_time_limits
        WHERE device_id = p_device_id;

        IF v_limit IS NOT NULL THEN
            v_limit_minutes := v_limit.daily_limit_minutes;
            v_limit_enabled := v_limit.enabled;
        ELSE
            v_limit_minutes := 120;
            v_limit_enabled := true;
        END IF;
    END IF;

    -- Calculate derived metrics defensively
    v_remaining_minutes := COALESCE(
        p_remaining_minutes,
        GREATEST(0, v_limit_minutes - p_total_usage_minutes)
    );

    v_is_limit_reached := COALESCE(
        p_is_limit_reached,
        (v_limit_enabled AND p_total_usage_minutes >= v_limit_minutes)
    );

    IF p_usage_percentage IS NOT NULL THEN
        v_usage_percentage := GREATEST(0, p_usage_percentage);
    ELSE
        IF v_limit_minutes = 0 THEN
            v_usage_percentage := CASE WHEN p_total_usage_minutes > 0 OR v_limit_enabled THEN 100 ELSE 0 END;
        ELSE
            v_usage_percentage := ROUND((p_total_usage_minutes::numeric / v_limit_minutes::numeric) * 100)::int;
        END IF;
    END IF;

    -- Atomic Upsert by (device_id, usage_date)
    INSERT INTO public.screen_time_usage_daily (
        family_id,
        device_id,
        usage_date,
        total_usage_minutes,
        daily_limit_minutes,
        remaining_minutes,
        usage_percentage,
        is_limit_reached,
        created_at,
        updated_at
    ) VALUES (
        v_device.family_id,
        p_device_id,
        p_usage_date,
        p_total_usage_minutes,
        v_limit_minutes,
        GREATEST(0, v_remaining_minutes),
        GREATEST(0, v_usage_percentage),
        v_is_limit_reached,
        v_now,
        v_now
    )
    ON CONFLICT (device_id, usage_date) DO UPDATE
    SET total_usage_minutes = EXCLUDED.total_usage_minutes,
        daily_limit_minutes = EXCLUDED.daily_limit_minutes,
        remaining_minutes = EXCLUDED.remaining_minutes,
        usage_percentage = EXCLUDED.usage_percentage,
        is_limit_reached = EXCLUDED.is_limit_reached,
        updated_at = v_now
    RETURNING * INTO v_usage;

    RETURN jsonb_build_object(
        'success', true,
        'usage', jsonb_build_object(
            'id', v_usage.id,
            'family_id', v_usage.family_id,
            'device_id', v_usage.device_id,
            'usage_date', v_usage.usage_date,
            'total_usage_minutes', v_usage.total_usage_minutes,
            'daily_limit_minutes', v_usage.daily_limit_minutes,
            'remaining_minutes', v_usage.remaining_minutes,
            'usage_percentage', v_usage.usage_percentage,
            'is_limit_reached', v_usage.is_limit_reached,
            'created_at', v_usage.created_at,
            'updated_at', v_usage.updated_at
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 7. Secure RPC: get_screen_time_usage_daily
-- Parent or verified paired child device reads daily usage.
CREATE OR REPLACE FUNCTION public.get_screen_time_usage_daily(
    p_device_id UUID,
    p_usage_date DATE DEFAULT CURRENT_DATE
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_usage RECORD;
    v_effective_date DATE := COALESCE(p_usage_date, CURRENT_DATE);
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

    -- Caller authorization
    IF v_caller_uid IS NOT NULL THEN
        IF v_device.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'You do not have permission to view usage for this device.'
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

    -- Query usage record
    SELECT * INTO v_usage
    FROM public.screen_time_usage_daily
    WHERE device_id = p_device_id
      AND usage_date = v_effective_date;

    IF v_usage IS NULL THEN
        RETURN jsonb_build_object(
            'success', true,
            'has_data', false,
            'usage', null
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'has_data', true,
        'usage', jsonb_build_object(
            'id', v_usage.id,
            'family_id', v_usage.family_id,
            'device_id', v_usage.device_id,
            'usage_date', v_usage.usage_date,
            'total_usage_minutes', v_usage.total_usage_minutes,
            'daily_limit_minutes', v_usage.daily_limit_minutes,
            'remaining_minutes', v_usage.remaining_minutes,
            'usage_percentage', v_usage.usage_percentage,
            'is_limit_reached', v_usage.is_limit_reached,
            'created_at', v_usage.created_at,
            'updated_at', v_usage.updated_at
        )
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- 8. Execution Privileges (Principle of Least Privilege)
REVOKE EXECUTE ON FUNCTION public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_screen_time_usage_daily(UUID, DATE) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.get_screen_time_usage_daily(UUID, DATE) TO authenticated, anon;

-- 9. Table Level Permissions (Subject to RLS)
GRANT ALL ON TABLE public.screen_time_usage_daily TO authenticated;
