-- ==============================================================================
-- NIQOB CLOUD MIGRATION: HARDEN USAGE & HEARTBEAT RPC AUTHORIZATION (SEC-01)
--
-- Objective:
--   Extend cryptographic child-device credential verification (established in Phase 5)
--   to the remaining unhardened child-facing RPCs:
--     1. public.upsert_screen_time_usage_daily
--     2. public.get_screen_time_usage_daily
--     3. public.update_device_heartbeat
--     4. public.get_screen_time_limit
--
-- Threat Addressed:
--   Prevent unauthorized/anonymous callers from spoofing screen-time usage, reading
--   daily usage metrics, forging heartbeats, or reading screen-time limits by merely
--   supplying a known child device UUID. Anonymous callers must prove possession
--   of the raw 256-bit device_secret issued during pair_child_device.
--
-- Cryptographic Model & Verification:
--   - Uses internal helper: public.verify_child_device_secret(p_device_id, p_device_secret)
--   - For unauthenticated callers (auth.uid() IS NULL): verifies secret format,
--     computes SHA-256 of the decoded 32-byte raw secret, matches against
--     public.devices.device_secret_hash, and confirms active CONNECTED pairing session.
--   - For authenticated parents (auth.uid() IS NOT NULL): maintains backward compatibility
--     where p_device_secret may be NULL, strictly verifying family ownership via
--     families.parent_id = auth.uid().
--
-- Information-Leakage Protection & Fail-Closed Policy:
--   - Child credential verification MUST occur BEFORE resource existence lookups
--     (prevents DEVICE_NOT_FOUND or oracle leakage to unauthenticated callers).
--   - Uniform generic UNAUTHORIZED returned on missing, malformed, invalid, or mismatched secrets.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Drop Insecure Overloads (Ensure no signatures without secret remain callable)
-- ------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN);
DROP FUNCTION IF EXISTS public.get_screen_time_usage_daily(UUID, DATE);
DROP FUNCTION IF EXISTS public.update_device_heartbeat(UUID);
DROP FUNCTION IF EXISTS public.get_screen_time_limit(UUID);

-- ------------------------------------------------------------------------------
-- 2. RPC: public.upsert_screen_time_usage_daily (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.upsert_screen_time_usage_daily(
    p_device_id UUID,
    p_usage_date DATE,
    p_total_usage_minutes INT,
    p_daily_limit_minutes INT DEFAULT NULL,
    p_remaining_minutes INT DEFAULT NULL,
    p_usage_percentage INT DEFAULT NULL,
    p_is_limit_reached BOOLEAN DEFAULT NULL,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_auth JSONB;
    v_limit RECORD;
    v_limit_minutes INT;
    v_limit_enabled BOOLEAN := true;
    v_remaining_minutes INT;
    v_usage_percentage INT;
    v_is_limit_reached BOOLEAN;
    v_usage RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- 1. Parameter Validation
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

    -- 2. Authorization Check (Child credential check happens BEFORE resource lookup)
    IF v_caller_uid IS NULL THEN
        -- Child caller: Authenticate device secret & active pairing first
        v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
        IF NOT (v_auth->>'authorized')::BOOLEAN THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Invalid device authorization.'
            );
        END IF;

        -- Device Lookup for child caller (to obtain family_id)
        SELECT d.id, d.family_id
        INTO v_device
        FROM public.devices d
        WHERE d.id = p_device_id;
    ELSE
        -- Authenticated parent caller: Lookup device and verify ownership of the family
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
                'message', 'You do not have permission to sync usage for this device.'
            );
        END IF;
    END IF;

    -- 3. Resolve configured limit if not provided
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

    -- 4. Calculate derived metrics defensively
    v_remaining_minutes := GREATEST(0, COALESCE(
        p_remaining_minutes,
        GREATEST(0, v_limit_minutes - p_total_usage_minutes)
    ));

    v_is_limit_reached := COALESCE(
        p_is_limit_reached,
        (v_limit_enabled AND p_total_usage_minutes >= v_limit_minutes)
    );

    IF p_usage_percentage IS NOT NULL THEN
        v_usage_percentage := GREATEST(0, p_usage_percentage);
    ELSE
        IF v_limit_minutes > 0 THEN
            v_usage_percentage := LEAST(100, ROUND((p_total_usage_minutes::NUMERIC / v_limit_minutes::NUMERIC) * 100)::INT);
        ELSE
            v_usage_percentage := 100;
        END IF;
    END IF;

    -- 5. Upsert daily usage record atomically
    INSERT INTO public.screen_time_usage_daily (
        family_id,
        device_id,
        usage_date,
        total_usage_minutes,
        daily_limit_minutes,
        remaining_minutes,
        usage_percentage,
        is_limit_reached,
        updated_at
    ) VALUES (
        v_device.family_id,
        p_device_id,
        p_usage_date,
        p_total_usage_minutes,
        v_limit_minutes,
        v_remaining_minutes,
        v_usage_percentage,
        v_is_limit_reached,
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

-- ------------------------------------------------------------------------------
-- 3. RPC: public.get_screen_time_usage_daily (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_screen_time_usage_daily(
    p_device_id UUID,
    p_usage_date DATE DEFAULT CURRENT_DATE,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_auth JSONB;
    v_usage RECORD;
    v_effective_date DATE := COALESCE(p_usage_date, CURRENT_DATE);
BEGIN
    -- 1. Parameter Validation
    IF p_device_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID cannot be null.'
        );
    END IF;

    -- 2. Caller Authorization
    IF v_caller_uid IS NULL THEN
        -- Child caller: Authenticate device secret & active pairing first
        v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
        IF NOT (v_auth->>'authorized')::BOOLEAN THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Invalid device authorization.'
            );
        END IF;

        SELECT d.id, d.family_id
        INTO v_device
        FROM public.devices d
        WHERE d.id = p_device_id;
    ELSE
        -- Authenticated parent caller: verify ownership of family
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
                'message', 'You do not have permission to view usage for this device.'
            );
        END IF;
    END IF;

    -- 3. Query usage record
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

-- ------------------------------------------------------------------------------
-- 4. RPC: public.update_device_heartbeat (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.update_device_heartbeat(
    p_device_id UUID,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_now TIMESTAMPTZ := NOW();
    v_device RECORD;
    v_auth JSONB;
    v_caller_uid UUID := auth.uid();
BEGIN
    -- 1. Parameter Validation
    IF p_device_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID cannot be null.'
        );
    END IF;

    -- 2. Caller Authorization
    IF v_caller_uid IS NULL THEN
        -- Unauthenticated child caller: Authenticate device secret & active pairing first
        v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
        IF NOT (v_auth->>'authorized')::BOOLEAN THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Invalid device authorization.'
            );
        END IF;

        SELECT d.*
        INTO v_device
        FROM public.devices d
        WHERE d.id = p_device_id;
    ELSE
        -- Authenticated parent caller: verify ownership of family
        SELECT d.*, f.parent_id
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
                'message', 'You do not have permission to update this device.'
            );
        END IF;
    END IF;

    -- 3. Throttle: If last seen less than 1 second ago, return success without DB write
    IF v_device.last_seen_at >= v_now - interval '1 second' THEN
        RETURN jsonb_build_object(
            'success', true,
            'device_id', p_device_id,
            'last_seen_at', v_device.last_seen_at
        );
    END IF;

    UPDATE public.devices
    SET last_seen_at = v_now,
        updated_at = v_now
    WHERE id = p_device_id;

    RETURN jsonb_build_object(
        'success', true,
        'device_id', p_device_id,
        'last_seen_at', v_now
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 5. RPC: public.get_screen_time_limit (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_screen_time_limit(
    p_device_id UUID,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_auth JSONB;
    v_limit RECORD;
BEGIN
    -- 1. Parameter Validation
    IF p_device_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID cannot be null.'
        );
    END IF;

    -- 2. Authorization Check
    IF v_caller_uid IS NULL THEN
        -- Unauthenticated child caller: Authenticate device secret & active pairing first
        v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
        IF NOT (v_auth->>'authorized')::BOOLEAN THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Invalid device authorization.'
            );
        END IF;

        SELECT d.id, d.family_id
        INTO v_device
        FROM public.devices d
        WHERE d.id = p_device_id;
    ELSE
        -- Authenticated parent caller: verify ownership of family
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
                'message', 'You do not have permission to view screen time for this device.'
            );
        END IF;
    END IF;

    -- 3. Query current limit
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
                'created_at', null,
                'updated_at', null
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

-- ------------------------------------------------------------------------------
-- 6. Execution Privileges (Principle of Least Privilege)
-- ------------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_screen_time_usage_daily(UUID, DATE, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.update_device_heartbeat(UUID, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_screen_time_limit(UUID, TEXT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN, TEXT) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.get_screen_time_usage_daily(UUID, DATE, TEXT) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.update_device_heartbeat(UUID, TEXT) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.get_screen_time_limit(UUID, TEXT) TO authenticated, anon;

-- PostgREST schema cache reload
NOTIFY pgrst, 'reload schema';
