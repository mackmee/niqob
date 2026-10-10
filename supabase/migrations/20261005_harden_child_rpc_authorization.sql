-- ==============================================================================
-- NIQOB CLOUD MIGRATION: HARDEN CHILD RPC AUTHORIZATION (PHASE 5 STEP 5-4)
--
-- Objective:
--   Enforce cryptographic child-device credential verification on sensitive
--   child-facing RPCs. Device ID alone is no longer accepted as authentication proof.
--
-- Threat Addressed:
--   Knowing a paired child device_id UUID is no longer sufficient to create, query,
--   or consume screen-time overrides. Callers must prove possession of the raw 256-bit
--   device_secret issued during pair_child_device.
--
-- Cryptographic Model:
--   - Client sends: p_device_id (UUID) + p_device_secret (64-char lowercase hex)
--   - Server computes: SHA-256 of the decoded 32-byte raw secret
--   - Server matches: computed hash against public.devices.device_secret_hash
--   - Internal helper: public.verify_child_device_secret(p_device_id, p_device_secret)
--   - Plaintext secret is NEVER persisted server-side.
--
-- Information-Leakage Protection & Fail-Closed Policy:
--   - Generic error responses reduce authorization-state information leakage.
--   - Child credential verification MUST occur BEFORE resource existence lookups
--     (prevents DEVICE_NOT_FOUND or REQUEST_NOT_FOUND oracle leakage to unauthenticated callers).
--   - NULL, blank, or malformed secrets (<> 64 hex chars) return generic UNAUTHORIZED.
--   - Unknown devices, legacy devices (device_secret_hash IS NULL), hash mismatches,
--     and unlinked/disconnected pairing sessions all return the identical generic UNAUTHORIZED.
--   - The internal helper is NOT directly executable by anon or authenticated roles;
--     it can only be invoked internally by outer SECURITY DEFINER RPCs.
--   - Minimal data return: Helper returns only {'authorized': true} on success and no sensitive metadata.
--   - Parent callers continue authenticating via auth.uid() family ownership.
--
-- Hardened RPCs:
--   1. public.create_screen_time_override_request (Child -> Parent request)
--   2. public.get_override_request_status (Status check)
--   3. public.consume_screen_time_override (Child atomic consumption)
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Internal Helper Function: public.verify_child_device_secret
-- ------------------------------------------------------------------------------
-- Internal authorization helper for outer SECURITY DEFINER RPCs.
-- Verifies secret format, compares computed SHA-256 against devices.device_secret_hash,
-- and confirms active CONNECTED pairing session membership.
-- Fails closed with uniform generic authorization errors to prevent oracle leakage.
-- Returns only {'authorized': true} on success; never leaks parent_id or secret data.
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.verify_child_device_secret(
    p_device_id UUID,
    p_device_secret TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_device RECORD;
    v_secret_trimmed TEXT;
    v_computed_hash TEXT;
BEGIN
    -- 1. Parameter Validation (Fail closed with uniform generic error)
    IF p_device_id IS NULL OR p_device_secret IS NULL THEN
        RETURN jsonb_build_object(
            'authorized', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    v_secret_trimmed := lower(trim(p_device_secret));

    -- Format verification: Must be exactly 64 lowercase hexadecimal characters (256 bits of entropy)
    IF length(v_secret_trimmed) != 64 OR v_secret_trimmed !~ '^[0-9a-f]{64}$' THEN
        RETURN jsonb_build_object(
            'authorized', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    -- 2. Device Lookup
    SELECT d.id, d.family_id, d.device_secret_hash
    INTO v_device
    FROM public.devices d
    WHERE d.id = p_device_id;

    IF v_device IS NULL THEN
        RETURN jsonb_build_object(
            'authorized', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    -- 3. Legacy Device Check (Fail closed for protected RPCs)
    IF v_device.device_secret_hash IS NULL THEN
        RETURN jsonb_build_object(
            'authorized', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    -- 4. Cryptographic Verification: Compute SHA-256 of the decoded 32-byte raw secret
    v_computed_hash := encode(
        digest(decode(v_secret_trimmed, 'hex'), 'sha256'),
        'hex'
    );

    IF v_device.device_secret_hash IS DISTINCT FROM v_computed_hash THEN
        RETURN jsonb_build_object(
            'authorized', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    -- 5. Active Pairing Check: Device must belong to an active CONNECTED pairing session
    IF NOT EXISTS (
        SELECT 1
        FROM public.pairing_sessions ps
        WHERE ps.child_device_id = p_device_id::text
          AND ps.family_id = v_device.family_id
          AND ps.status = 'CONNECTED'
    ) THEN
        RETURN jsonb_build_object(
            'authorized', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Invalid device authorization.'
        );
    END IF;

    -- Authorized successfully (minimal response; no parent_id or family metadata leaked)
    RETURN jsonb_build_object(
        'authorized', true
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 2. Drop Insecure Overloads (Ensure no legacy signatures without secret remain callable)
-- ------------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.create_screen_time_override_request(UUID, INT, TEXT);
DROP FUNCTION IF EXISTS public.get_override_request_status(UUID, UUID);
DROP FUNCTION IF EXISTS public.consume_screen_time_override(UUID, UUID);

-- ------------------------------------------------------------------------------
-- 3. RPC: public.create_screen_time_override_request (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.create_screen_time_override_request(
    p_device_id UUID,
    p_requested_duration_minutes INT,
    p_reason TEXT DEFAULT NULL,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
    v_auth JSONB;
    v_request_id UUID;
    v_claim_expires_at TIMESTAMPTZ;
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

    IF p_requested_duration_minutes IS NULL OR p_requested_duration_minutes NOT IN (15, 30, 60) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_DURATION',
            'message', 'Requested duration must be exactly 15, 30, or 60 minutes.'
        );
    END IF;

    IF p_reason IS NOT NULL AND length(p_reason) > 200 THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_REASON',
            'message', 'Reason cannot exceed 200 characters.'
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
                'message', 'You do not have permission to create an override request for this device.'
            );
        END IF;
    END IF;

    -- 3. Defensive lazy-cleanup: Expire any existing PENDING request whose claim window elapsed
    UPDATE public.screen_time_override_requests
    SET status = 'EXPIRED',
        updated_at = v_now
    WHERE device_id = p_device_id
      AND status = 'PENDING'
      AND claim_expires_at <= v_now;

    -- 4. Check if an active PENDING request already exists
    IF EXISTS (
        SELECT 1
        FROM public.screen_time_override_requests
        WHERE device_id = p_device_id
          AND status = 'PENDING'
    ) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'PENDING_REQUEST_EXISTS',
            'message', 'An active override request is already pending approval for this device.'
        );
    END IF;

    -- 5. Claim window calculation: 15 minutes to decide/claim
    v_claim_expires_at := v_now + INTERVAL '15 minutes';

    -- 6. Insert new request
    INSERT INTO public.screen_time_override_requests (
        family_id,
        device_id,
        requested_duration_minutes,
        status,
        reason,
        created_at,
        claim_expires_at
    ) VALUES (
        v_device.family_id,
        p_device_id,
        p_requested_duration_minutes,
        'PENDING',
        trim(p_reason),
        v_now,
        v_claim_expires_at
    )
    RETURNING id INTO v_request_id;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_request_id,
        'device_id', p_device_id,
        'requested_duration_minutes', p_requested_duration_minutes,
        'status', 'PENDING',
        'reason', trim(p_reason),
        'created_at', v_now,
        'claim_expires_at', v_claim_expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 4. RPC: public.get_override_request_status (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_override_request_status(
    p_device_id UUID,
    p_request_id UUID,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
    v_auth JSONB;
    v_now TIMESTAMPTZ := NOW();
    v_is_active BOOLEAN := false;
    v_is_expired BOOLEAN := false;
    v_remaining_seconds INT := 0;
BEGIN
    -- 1. Parameter Validation
    IF p_device_id IS NULL OR p_request_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID and Request ID cannot be null.'
        );
    END IF;

    -- 2. Child Credential Verification (MUST occur BEFORE resource existence lookups)
    IF v_caller_uid IS NULL THEN
        v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
        IF NOT (v_auth->>'authorized')::BOOLEAN THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Invalid device authorization.'
            );
        END IF;
    END IF;

    -- 3. Query override request
    SELECT stor.*, f.parent_id
    INTO v_request
    FROM public.screen_time_override_requests stor
    JOIN public.families f ON f.id = stor.family_id
    WHERE stor.id = p_request_id;

    IF v_request IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'REQUEST_NOT_FOUND',
            'message', 'Override request not found.'
        );
    END IF;

    -- 4. Device Binding Check
    IF v_request.device_id != p_device_id THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Request does not belong to specified device.'
        );
    END IF;

    -- 5. Parent Authorization Check
    IF v_caller_uid IS NOT NULL THEN
        IF v_request.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'You do not have permission to view this request.'
            );
        END IF;
    END IF;

    -- 6. Lazy Expiration for PENDING or APPROVED requests past claim deadline
    IF v_request.status IN ('PENDING', 'APPROVED') AND v_request.claim_expires_at <= v_now THEN
        UPDATE public.screen_time_override_requests
        SET status = 'EXPIRED',
            updated_at = v_now
        WHERE id = p_request_id;
        v_request.status := 'EXPIRED';
    END IF;

    -- 7. Compute Derived State Fields
    IF v_request.status = 'CONSUMED' THEN
        v_is_active := (v_request.consumed_expires_at IS NOT NULL AND v_now < v_request.consumed_expires_at);
        v_is_expired := (v_request.consumed_expires_at IS NOT NULL AND v_now >= v_request.consumed_expires_at);
        v_remaining_seconds := GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (v_request.consumed_expires_at - v_now))))::INT;
    ELSIF v_request.status = 'EXPIRED' THEN
        v_is_active := false;
        v_is_expired := true;
        v_remaining_seconds := 0;
    ELSE
        v_is_active := false;
        v_is_expired := false;
        v_remaining_seconds := 0;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_request.id,
        'device_id', v_request.device_id,
        'requested_duration_minutes', v_request.requested_duration_minutes,
        'approved_duration_minutes', v_request.approved_duration_minutes,
        'status', v_request.status,
        'reason', v_request.reason,
        'rejection_reason', v_request.rejection_reason,
        'created_at', v_request.created_at,
        'claim_expires_at', v_request.claim_expires_at,
        'decided_at', v_request.decided_at,
        'consumed_at', v_request.consumed_at,
        'consumed_expires_at', v_request.consumed_expires_at,
        'revoked_at', v_request.revoked_at,
        'is_active', v_is_active,
        'is_expired', v_is_expired,
        'remaining_seconds', v_remaining_seconds
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 5. RPC: public.consume_screen_time_override (Hardened)
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.consume_screen_time_override(
    p_device_id UUID,
    p_request_id UUID,
    p_device_secret TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
    v_auth JSONB;
    v_updated RECORD;
    v_now TIMESTAMPTZ := NOW();
    v_consumed_expires_at TIMESTAMPTZ;
BEGIN
    -- 1. Parameter Validation
    IF p_device_id IS NULL OR p_request_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Device ID and Request ID cannot be null.'
        );
    END IF;

    -- 2. Child Credential Verification (MUST occur BEFORE resource existence lookups)
    IF v_caller_uid IS NULL THEN
        v_auth := public.verify_child_device_secret(p_device_id, p_device_secret);
        IF NOT (v_auth->>'authorized')::BOOLEAN THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Invalid device authorization.'
            );
        END IF;
    END IF;

    -- 3. Lookup request
    SELECT stor.*, f.parent_id
    INTO v_request
    FROM public.screen_time_override_requests stor
    JOIN public.families f ON f.id = stor.family_id
    WHERE stor.id = p_request_id;

    IF v_request IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'REQUEST_NOT_FOUND',
            'message', 'Override request not found.'
        );
    END IF;

    -- 4. Device Binding Check
    IF v_request.device_id != p_device_id THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Request does not belong to specified device.'
        );
    END IF;

    -- 5. Parent Authorization Check
    IF v_caller_uid IS NOT NULL THEN
        IF v_request.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Permission denied.'
            );
        END IF;
    END IF;

    -- 6. Status & Double-Spend Check
    IF v_request.status = 'CONSUMED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'ALREADY_CONSUMED',
            'message', 'This override has already been consumed.'
        );
    END IF;

    IF v_request.status = 'REJECTED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'REQUEST_REJECTED',
            'message', 'This request was rejected by the parent.'
        );
    END IF;

    IF v_request.status = 'REVOKED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'REQUEST_REVOKED',
            'message', 'This request has been revoked.'
        );
    END IF;

    IF v_request.status = 'EXPIRED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'EXPIRED',
            'message', 'This override request has expired.'
        );
    END IF;

    IF v_request.status != 'APPROVED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'NOT_APPROVED',
            'message', 'Override request is not approved.'
        );
    END IF;

    -- 7. Claim Expiration Check
    IF v_request.claim_expires_at <= v_now THEN
        UPDATE public.screen_time_override_requests
        SET status = 'EXPIRED',
            updated_at = v_now
        WHERE id = p_request_id;

        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'EXPIRED',
            'message', 'Approval claim window has expired.'
        );
    END IF;

    -- 8. Sanity Check on Approved Duration
    IF v_request.approved_duration_minutes IS NULL OR v_request.approved_duration_minutes NOT IN (15, 30, 60) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_APPROVED_DURATION',
            'message', 'Corrupt override request: invalid approved duration.'
        );
    END IF;

    -- 9. Calculate Server-Authoritative Expiration Window
    v_consumed_expires_at := v_now + (v_request.approved_duration_minutes || ' minutes')::INTERVAL;

    -- 10. Atomic State Transition: APPROVED -> CONSUMED
    UPDATE public.screen_time_override_requests
    SET status = 'CONSUMED',
        consumed_at = v_now,
        consumed_expires_at = v_consumed_expires_at,
        updated_at = v_now
    WHERE id = p_request_id
      AND status = 'APPROVED'
    RETURNING * INTO v_updated;

    IF v_updated IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'CONCURRENT_CONSUME_CONFLICT',
            'message', 'Failed to consume: request was modified concurrently.'
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_updated.id,
        'device_id', v_updated.device_id,
        'approved_duration_minutes', v_updated.approved_duration_minutes,
        'status', 'CONSUMED',
        'consumed_at', v_updated.consumed_at,
        'consumed_expires_at', v_updated.consumed_expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 6. Execution Privileges (Principle of Least Privilege)
-- ------------------------------------------------------------------------------
-- Revoke direct execution on internal helper function from PUBLIC, anon, and authenticated roles.
-- The internal helper can ONLY be executed internally by outer SECURITY DEFINER RPCs.
REVOKE EXECUTE ON FUNCTION public.verify_child_device_secret(UUID, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.verify_child_device_secret(UUID, TEXT) FROM anon, authenticated;

-- Revoke PUBLIC execution on public-facing RPCs
REVOKE EXECUTE ON FUNCTION public.create_screen_time_override_request(UUID, INT, TEXT, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_override_request_status(UUID, UUID, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.consume_screen_time_override(UUID, UUID, TEXT) FROM PUBLIC;

-- Grant execution privileges on public-facing RPCs to authenticated parent role and anon child role
GRANT EXECUTE ON FUNCTION public.create_screen_time_override_request(UUID, INT, TEXT, TEXT) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.get_override_request_status(UUID, UUID, TEXT) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.consume_screen_time_override(UUID, UUID, TEXT) TO authenticated, anon;
