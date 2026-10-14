-- ==============================================================================
-- NIQOB CLOUD MIGRATION: ENFORCE CHILD SECRET FOR SCREEN-TIME TELEMETRY WRITE (SEC-03 Step 7D)
--
-- Objective:
--   Prevent parent-authenticated callers from writing or overwriting child
--   screen-time telemetry in public.screen_time_usage_daily. Telemetry write/upsert
--   must strictly require valid child-device cryptographic authorization for every caller.
--
-- Background & Threat Addressed:
--   In SEC-01 migration 20261011_harden_usage_and_heartbeat_rpc_authorization.sql,
--   upsert_screen_time_usage_daily included a fallback branch:
--     IF v_caller_uid IS NULL THEN (verify_child_device_secret)
--     ELSE (verify families.parent_id = auth.uid())
--   This allowed parent-authenticated sessions (auth.uid() IS NOT NULL) to write
--   or overwrite child telemetry without supplying the child device secret.
--   During Step 7A/7B audit, this allowed a parent device running a background sync worker
--   to upload parent device screen-time metrics under the child's device ID.
--
-- Enforcement Policy:
--   1. upsert_screen_time_usage_daily requires public.verify_child_device_secret(p_device_id, p_device_secret)
--      for EVERY caller, regardless of whether auth.uid() is NULL or an authenticated parent.
--   2. A parent JWT alone (even with matching family ownership) CANNOT authorize a telemetry write.
--   3. Missing, blank, invalid, or revoked child credentials fail closed with the repository's
--      uniform generic authorization error:
--        {'success': false, 'error_code': 'UNAUTHORIZED', 'message': 'Invalid device authorization.'}
--   4. Parent read access through public.get_screen_time_usage_daily is fully preserved.
--   5. Existing daily upsert semantics, screen-time calculations, and valid child sync are preserved.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. RPC: public.upsert_screen_time_usage_daily (Enforce Child Secret for All Callers)
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

    -- 2. Authorization Check (Fail closed: require valid child device secret for EVERY caller)
    -- Telemetry write is restricted strictly to authorized child devices.
    -- An authenticated parent JWT alone (auth.uid() IS NOT NULL) MUST NOT authorize telemetry writes.
    -- Every caller must prove possession of the raw device secret for an active CONNECTED pairing session.
    v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
    IF NOT (v_auth->>'authorized')::BOOLEAN THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    -- Device Lookup (to obtain family_id for daily usage record)
    SELECT d.id, d.family_id
    INTO v_device
    FROM public.devices d
    WHERE d.id = p_device_id;

    IF v_device IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
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
-- 2. Execution Privileges (Preserve RPC Compatibility)
-- ------------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.upsert_screen_time_usage_daily(UUID, DATE, INT, INT, INT, INT, BOOLEAN, TEXT) TO authenticated, anon;

-- PostgREST schema cache reload
NOTIFY pgrst, 'reload schema';
