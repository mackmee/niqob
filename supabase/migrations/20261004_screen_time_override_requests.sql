-- ==============================================================================
-- NIQOB CLOUD MIGRATION: REMOTE SCREEN TIME OVERRIDE REQUESTS (PHASE 4F-2)
-- Table: public.screen_time_override_requests
-- RPCs:
--   1. public.create_screen_time_override_request (Child -> Parent request)
--   2. public.get_pending_override_requests (Parent queue)
--   3. public.get_override_request_status (Shared status check)
--   4. public.approve_screen_time_override_request (Parent approval)
--   5. public.reject_screen_time_override_request (Parent rejection)
--   6. public.consume_screen_time_override (Child atomic consumption)
--   7. public.revoke_screen_time_override (Parent emergency revocation)
--
-- Security Architecture:
--   - Strict Family Isolation enforced via auth.uid() = families.parent_id
--   - Non-bypassable partial unique index: at most ONE PENDING request per child device
--   - Single-use atomic consumption: status APPROVED -> CONSUMED
--   - Immutable CONSUMED audit state (never overwritten to EXPIRED)
--   - Child RPCs mediated via SECURITY DEFINER with active pairing verification
--
-- Critical Child Authorization Note:
--   Current Phase 4F-2 child RPC authorization uses the existing anon + device_id + CONNECTED pairing trust model.
--   device_id is an identifier, not a cryptographic credential.
--   Stronger device authentication is deferred to a future phase.
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Table: public.screen_time_override_requests
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.screen_time_override_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    family_id UUID NOT NULL REFERENCES public.families(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    requested_duration_minutes INT NOT NULL
        CHECK (requested_duration_minutes IN (15, 30, 60)),
    approved_duration_minutes INT
        CHECK (approved_duration_minutes IS NULL OR approved_duration_minutes IN (15, 30, 60)),
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'REVOKED', 'CONSUMED')),
    reason TEXT
        CHECK (reason IS NULL OR length(reason) <= 200),
    rejection_reason TEXT
        CHECK (rejection_reason IS NULL OR length(rejection_reason) <= 200),

    -- Request & Claim Lifecycle
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    claim_expires_at TIMESTAMPTZ NOT NULL,

    -- Parent Decision Audit
    decided_at TIMESTAMPTZ,
    decided_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,

    -- Child Consumption Audit
    consumed_at TIMESTAMPTZ,
    consumed_expires_at TIMESTAMPTZ,

    -- Parent Revocation Audit
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,

    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ------------------------------------------------------------------------------
-- 2. Indexes for Query Performance & Data Integrity
-- ------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_screen_time_override_requests_family_id
    ON public.screen_time_override_requests(family_id);

CREATE INDEX IF NOT EXISTS idx_screen_time_override_requests_device_id
    ON public.screen_time_override_requests(device_id);

CREATE INDEX IF NOT EXISTS idx_screen_time_override_requests_status
    ON public.screen_time_override_requests(status);

CREATE INDEX IF NOT EXISTS idx_screen_time_override_requests_created_at
    ON public.screen_time_override_requests(created_at DESC);

-- Partial unique index: Guarantees at database level that a child device
-- can have at most ONE active PENDING request at any time.
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_pending_override_per_device
    ON public.screen_time_override_requests (device_id)
    WHERE status = 'PENDING';

-- ------------------------------------------------------------------------------
-- 3. Automatic updated_at Trigger
-- ------------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trigger_screen_time_override_requests_updated_at ON public.screen_time_override_requests;
CREATE TRIGGER trigger_screen_time_override_requests_updated_at
    BEFORE UPDATE ON public.screen_time_override_requests
    FOR EACH ROW EXECUTE FUNCTION public.set_current_timestamp_updated_at();

-- ------------------------------------------------------------------------------
-- 4. Row Level Security (RLS)
-- ------------------------------------------------------------------------------
ALTER TABLE public.screen_time_override_requests ENABLE ROW LEVEL SECURITY;

-- Parent SELECT Policy: Parents can only view override requests for devices in their family
DROP POLICY IF EXISTS "Parents can view override requests for their family" ON public.screen_time_override_requests;
CREATE POLICY "Parents can view override requests for their family"
    ON public.screen_time_override_requests
    FOR SELECT
    USING (
        family_id IN (
            SELECT id FROM public.families WHERE parent_id = auth.uid()
        )
    );

-- SECURITY ARCHITECTURE NOTE ON RLS & CHILD ACCESS:
-- Direct table access is granted exclusively to authenticated parents via RLS.
-- Child devices do NOT have direct SELECT, INSERT, or UPDATE policies on this table,
-- preventing any potential cross-device or cross-family data leakage.
-- All child device interactions are mediated exclusively through SECURITY DEFINER RPCs:
--   1. create_screen_time_override_request
--   2. get_override_request_status
--   3. consume_screen_time_override
-- Each RPC independently validates device binding and active pairing in public.pairing_sessions.
-- Therefore, an overly permissive anon table policy is completely unnecessary and avoided.

-- ------------------------------------------------------------------------------
-- 5. RPC 1: create_screen_time_override_request
-- Caller: paired child device (anon) or parent
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.create_screen_time_override_request(
    p_device_id UUID,
    p_requested_duration_minutes INT,
    p_reason TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_device RECORD;
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

    -- 2. Verify device existence and derive family_id securely from the database
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

    -- 3. Authorization Check
    IF v_caller_uid IS NOT NULL THEN
        -- Authenticated parent caller: verify ownership of the family
        IF v_device.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'You do not have permission to create an override request for this device.'
            );
        END IF;
    ELSE
        -- Unauthenticated child device: Must be an actively paired device in this family
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

    -- 4. Defensive lazy-cleanup: Expire any existing PENDING request whose claim window elapsed
    UPDATE public.screen_time_override_requests
    SET status = 'EXPIRED',
        updated_at = v_now
    WHERE device_id = p_device_id
      AND status = 'PENDING'
      AND claim_expires_at <= v_now;

    -- 5. Check if an active PENDING request already exists
    IF EXISTS (
        SELECT 1
        FROM public.screen_time_override_requests
        WHERE device_id = p_device_id
          AND status = 'PENDING'
    ) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'PENDING_REQUEST_EXISTS',
            'message', 'A pending override request already exists for this device.'
        );
    END IF;

    -- 6. Insert new PENDING request with 15-minute claim deadline
    v_claim_expires_at := v_now + INTERVAL '15 minutes';

    INSERT INTO public.screen_time_override_requests (
        family_id,
        device_id,
        requested_duration_minutes,
        status,
        reason,
        claim_expires_at,
        created_at,
        updated_at
    ) VALUES (
        v_device.family_id,
        p_device_id,
        p_requested_duration_minutes,
        'PENDING',
        p_reason,
        v_claim_expires_at,
        v_now,
        v_now
    )
    RETURNING id INTO v_request_id;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_request_id,
        'status', 'PENDING',
        'claim_expires_at', v_claim_expires_at
    );

EXCEPTION
    WHEN unique_violation THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'PENDING_REQUEST_EXISTS',
            'message', 'A pending override request already exists for this device.'
        );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 6. RPC 2: get_pending_override_requests
-- Caller: authenticated parent
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_pending_override_requests(
    p_device_id UUID DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_now TIMESTAMPTZ := NOW();
    v_requests JSONB;
BEGIN
    -- 1. Authorization: Require authenticated parent
    IF v_caller_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Authentication required.'
        );
    END IF;

    -- 2. If p_device_id is provided, verify device belongs to a family owned by caller
    IF p_device_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
            FROM public.devices d
            JOIN public.families f ON f.id = d.family_id
            WHERE d.id = p_device_id
              AND f.parent_id = v_caller_uid
        ) THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'DEVICE_NOT_FOUND',
                'message', 'Device not found or not in your family.'
            );
        END IF;
    END IF;

    -- 3. Lazily mark expired PENDING rows in caller's family as EXPIRED
    UPDATE public.screen_time_override_requests stor
    SET status = 'EXPIRED',
        updated_at = v_now
    FROM public.families f
    WHERE stor.family_id = f.id
      AND f.parent_id = v_caller_uid
      AND stor.status = 'PENDING'
      AND stor.claim_expires_at <= v_now;

    -- 4. Query active PENDING requests for caller's family
    SELECT COALESCE(jsonb_agg(
        jsonb_build_object(
            'request_id', stor.id,
            'device_id', stor.device_id,
            'requested_duration_minutes', stor.requested_duration_minutes,
            'approved_duration_minutes', stor.approved_duration_minutes,
            'status', stor.status,
            'reason', stor.reason,
            'rejection_reason', stor.rejection_reason,
            'created_at', stor.created_at,
            'claim_expires_at', stor.claim_expires_at,
            'decided_at', stor.decided_at,
            'consumed_at', stor.consumed_at,
            'consumed_expires_at', stor.consumed_expires_at,
            'revoked_at', stor.revoked_at
        ) ORDER BY stor.created_at ASC
    ), '[]'::jsonb)
    INTO v_requests
    FROM public.screen_time_override_requests stor
    JOIN public.families f ON f.id = stor.family_id
    WHERE f.parent_id = v_caller_uid
      AND stor.status = 'PENDING'
      AND (p_device_id IS NULL OR stor.device_id = p_device_id);

    RETURN jsonb_build_object(
        'success', true,
        'requests', v_requests
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 7. RPC 3: get_override_request_status
-- Caller: authenticated parent OR paired child device
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_override_request_status(
    p_device_id UUID,
    p_request_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
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

    -- 2. Query override request
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

    -- 3. Device Binding Check
    IF v_request.device_id != p_device_id THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Request does not belong to specified device.'
        );
    END IF;

    -- 4. Authorization Check
    IF v_caller_uid IS NOT NULL THEN
        -- Parent caller must own the family
        IF v_request.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'You do not have permission to view this request.'
            );
        END IF;
    ELSE
        -- Child caller must be actively connected
        IF NOT EXISTS (
            SELECT 1
            FROM public.pairing_sessions ps
            WHERE ps.child_device_id = p_device_id::text
              AND ps.family_id = v_request.family_id
              AND ps.status = 'CONNECTED'
        ) THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Access denied: device is not an active connected child device.'
            );
        END IF;
    END IF;

    -- 5. Lazy Expiration for PENDING or APPROVED requests past claim deadline
    IF v_request.status IN ('PENDING', 'APPROVED') AND v_request.claim_expires_at <= v_now THEN
        UPDATE public.screen_time_override_requests
        SET status = 'EXPIRED',
            updated_at = v_now
        WHERE id = p_request_id;
        v_request.status := 'EXPIRED';
    END IF;

    -- 6. Compute Derived State Fields
    -- Note: CONSUMED status is preserved permanently for audit fidelity.
    IF v_request.status = 'CONSUMED' THEN
        v_is_active := (v_request.consumed_expires_at IS NOT NULL AND v_now < v_request.consumed_expires_at);
        v_is_expired := (v_request.consumed_expires_at IS NOT NULL AND v_now >= v_request.consumed_expires_at);
        v_remaining_seconds := GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (v_request.consumed_expires_at - v_now))))::INT;
    ELSIF v_request.status = 'EXPIRED' THEN
        v_is_active := false;
        v_is_expired := true;
        v_remaining_seconds := 0;
    ELSIF v_request.status = 'APPROVED' THEN
        v_is_active := false;
        v_is_expired := (v_request.claim_expires_at <= v_now);
        v_remaining_seconds := GREATEST(0, FLOOR(EXTRACT(EPOCH FROM (v_request.claim_expires_at - v_now))))::INT;
    ELSE
        v_is_active := false;
        v_is_expired := false;
        v_remaining_seconds := 0;
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_request.id,
        'device_id', v_request.device_id,
        'status', v_request.status,
        'requested_duration_minutes', v_request.requested_duration_minutes,
        'approved_duration_minutes', v_request.approved_duration_minutes,
        'reason', v_request.reason,
        'rejection_reason', v_request.rejection_reason,
        'created_at', v_request.created_at,
        'claim_expires_at', v_request.claim_expires_at,
        'decided_at', v_request.decided_at,
        'consumed_at', v_request.consumed_at,
        'consumed_expires_at', v_request.consumed_expires_at,
        'is_active', v_is_active,
        'is_expired', v_is_expired,
        'remaining_seconds', v_remaining_seconds
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 8. RPC 4: approve_screen_time_override_request
-- Caller: authenticated parent
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.approve_screen_time_override_request(
    p_request_id UUID,
    p_approved_duration_minutes INT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
    v_updated RECORD;
    v_duration INT;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- 1. Authorization: Require authenticated parent
    IF v_caller_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Authentication required.'
        );
    END IF;

    -- 2. Parameter Validation
    IF p_request_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Request ID cannot be null.'
        );
    END IF;

    IF p_approved_duration_minutes IS NOT NULL AND p_approved_duration_minutes NOT IN (15, 30, 60) THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_DURATION',
            'message', 'Approved duration must be 15, 30, or 60 minutes.'
        );
    END IF;

    -- 3. Lookup request and verify family ownership
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

    IF v_request.parent_id != v_caller_uid THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'You do not have permission to approve this request.'
        );
    END IF;

    -- 4. Status Check: Must be PENDING
    IF v_request.status != 'PENDING' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_STATUS',
            'message', format('Request is not pending (current status: %s).', v_request.status)
        );
    END IF;

    -- 5. Check if claim window already expired
    IF v_request.claim_expires_at <= v_now THEN
        UPDATE public.screen_time_override_requests
        SET status = 'EXPIRED',
            updated_at = v_now
        WHERE id = p_request_id;

        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'REQUEST_EXPIRED',
            'message', 'Pending request has expired and cannot be approved.'
        );
    END IF;

    -- 6. Resolve approved duration (default to requested duration if not overridden)
    v_duration := COALESCE(p_approved_duration_minutes, v_request.requested_duration_minutes);

    -- 7. Atomic update to APPROVED with a 15-minute window for the child to consume
    UPDATE public.screen_time_override_requests
    SET status = 'APPROVED',
        approved_duration_minutes = v_duration,
        decided_at = v_now,
        decided_by = v_caller_uid,
        claim_expires_at = v_now + INTERVAL '15 minutes',
        updated_at = v_now
    WHERE id = p_request_id
      AND status = 'PENDING'
    RETURNING * INTO v_updated;

    IF v_updated IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'CONFLICT',
            'message', 'Request status changed concurrently.'
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_updated.id,
        'status', 'APPROVED',
        'approved_duration_minutes', v_updated.approved_duration_minutes,
        'decided_at', v_updated.decided_at,
        'claim_expires_at', v_updated.claim_expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 9. RPC 5: reject_screen_time_override_request
-- Caller: authenticated parent
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.reject_screen_time_override_request(
    p_request_id UUID,
    p_reason TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
    v_updated RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- 1. Authorization: Require authenticated parent
    IF v_caller_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Authentication required.'
        );
    END IF;

    -- 2. Parameter Validation
    IF p_request_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Request ID cannot be null.'
        );
    END IF;

    IF p_reason IS NOT NULL AND length(p_reason) > 200 THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_REASON',
            'message', 'Rejection reason cannot exceed 200 characters.'
        );
    END IF;

    -- 3. Lookup request and verify family ownership
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

    IF v_request.parent_id != v_caller_uid THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'You do not have permission to reject this request.'
        );
    END IF;

    -- 4. Status Check: Must be PENDING
    IF v_request.status != 'PENDING' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_STATUS',
            'message', format('Request is not pending (current status: %s).', v_request.status)
        );
    END IF;

    -- 5. Atomic update to REJECTED
    UPDATE public.screen_time_override_requests
    SET status = 'REJECTED',
        rejection_reason = p_reason,
        decided_at = v_now,
        decided_by = v_caller_uid,
        updated_at = v_now
    WHERE id = p_request_id
      AND status = 'PENDING'
    RETURNING * INTO v_updated;

    IF v_updated IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'CONFLICT',
            'message', 'Request status changed concurrently.'
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_updated.id,
        'status', 'REJECTED',
        'rejection_reason', v_updated.rejection_reason,
        'decided_at', v_updated.decided_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 10. RPC 6: consume_screen_time_override
-- Caller: paired child device
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.consume_screen_time_override(
    p_device_id UUID,
    p_request_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
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

    -- 2. Lookup request
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

    -- 3. Device Binding Check: Request must belong strictly to this device
    IF v_request.device_id != p_device_id THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Request does not belong to specified device.'
        );
    END IF;

    -- 4. Authorization Check
    IF v_caller_uid IS NOT NULL THEN
        -- Authenticated parent caller: verify ownership of family
        IF v_request.parent_id != v_caller_uid THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Permission denied.'
            );
        END IF;
    ELSE
        -- Unauthenticated child caller: Verify active CONNECTED pairing session
        IF NOT EXISTS (
            SELECT 1
            FROM public.pairing_sessions ps
            WHERE ps.child_device_id = p_device_id::text
              AND ps.family_id = v_request.family_id
              AND ps.status = 'CONNECTED'
        ) THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'UNAUTHORIZED',
                'message', 'Access denied: device is not an active connected child device.'
            );
        END IF;
    END IF;

    -- 5. Status & Double-Spend Check
    IF v_request.status = 'CONSUMED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'ALREADY_CONSUMED',
            'message', 'This override has already been consumed.'
        );
    END IF;

    IF v_request.status != 'APPROVED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_STATUS',
            'message', format('Override request is not approved (current status: %s).', v_request.status)
        );
    END IF;

    -- 6. Claim Deadline Check
    IF v_request.claim_expires_at <= v_now THEN
        UPDATE public.screen_time_override_requests
        SET status = 'EXPIRED',
            updated_at = v_now
        WHERE id = p_request_id;

        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'CLAIM_WINDOW_EXPIRED',
            'message', 'Approval claim window has expired.'
        );
    END IF;

    -- 7. Server-Authoritative Consumption Calculation
    -- The server calculates consumed_expires_at using the approved duration.
    -- Client CANNOT provide or alter duration or expiration timestamps.
    v_consumed_expires_at := v_now + (v_request.approved_duration_minutes * INTERVAL '1 minute');

    -- 8. Atomic Transition: APPROVED -> CONSUMED
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
            'error_code', 'ALREADY_CONSUMED',
            'message', 'Override was already consumed or modified.'
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'status', 'CONSUMED',
        'request_id', v_updated.id,
        'approved_duration_minutes', v_updated.approved_duration_minutes,
        'consumed_at', v_updated.consumed_at,
        'consumed_expires_at', v_updated.consumed_expires_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 11. RPC 7: revoke_screen_time_override
-- Caller: authenticated parent
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.revoke_screen_time_override(
    p_request_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_caller_uid UUID := auth.uid();
    v_request RECORD;
    v_updated RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- 1. Authorization: Require authenticated parent
    IF v_caller_uid IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'Authentication required.'
        );
    END IF;

    -- 2. Parameter Validation
    IF p_request_id IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_PARAMETERS',
            'message', 'Request ID cannot be null.'
        );
    END IF;

    -- 3. Lookup request and verify family ownership
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

    IF v_request.parent_id != v_caller_uid THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'UNAUTHORIZED',
            'message', 'You do not have permission to revoke this request.'
        );
    END IF;

    -- 4. Status Checks
    IF v_request.status = 'REVOKED' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'ALREADY_REVOKED',
            'message', 'Override is already revoked.'
        );
    END IF;

    IF v_request.status = 'CONSUMED' AND v_request.consumed_expires_at <= v_now THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'ALREADY_EXPIRED',
            'message', 'Override has already expired naturally and cannot be revoked.'
        );
    END IF;

    IF v_request.status NOT IN ('APPROVED', 'CONSUMED') THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_STATUS',
            'message', format('Only APPROVED or active CONSUMED overrides can be revoked (current status: %s).', v_request.status)
        );
    END IF;

    -- 5. Atomic update to REVOKED
    UPDATE public.screen_time_override_requests
    SET status = 'REVOKED',
        revoked_at = v_now,
        revoked_by = v_caller_uid,
        updated_at = v_now
    WHERE id = p_request_id
      AND status IN ('APPROVED', 'CONSUMED')
    RETURNING * INTO v_updated;

    IF v_updated IS NULL THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'CONFLICT',
            'message', 'Request status changed concurrently.'
        );
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'request_id', v_updated.id,
        'status', 'REVOKED',
        'revoked_at', v_updated.revoked_at
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 12. Execution Privileges (Principle of Least Privilege)
-- ------------------------------------------------------------------------------
-- Revoke all direct PUBLIC execution privileges
REVOKE EXECUTE ON FUNCTION public.create_screen_time_override_request(UUID, INT, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_pending_override_requests(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_override_request_status(UUID, UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.approve_screen_time_override_request(UUID, INT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.reject_screen_time_override_request(UUID, TEXT) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.consume_screen_time_override(UUID, UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.revoke_screen_time_override(UUID) FROM PUBLIC;

-- Child RPCs: callable by anon (child devices) and authenticated (parents/testing)
GRANT EXECUTE ON FUNCTION public.create_screen_time_override_request(UUID, INT, TEXT) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.get_override_request_status(UUID, UUID) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.consume_screen_time_override(UUID, UUID) TO authenticated, anon;

-- Parent RPCs: callable ONLY by authenticated parent accounts
GRANT EXECUTE ON FUNCTION public.get_pending_override_requests(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.approve_screen_time_override_request(UUID, INT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.reject_screen_time_override_request(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_screen_time_override(UUID) TO authenticated;

-- ------------------------------------------------------------------------------
-- 13. Table Level Permissions
-- ------------------------------------------------------------------------------
-- Direct table access granted to authenticated parent accounts (governed by RLS)
GRANT SELECT ON TABLE public.screen_time_override_requests TO authenticated;

-- Do NOT grant direct table permissions to anon; child devices must operate
-- strictly through the SECURITY DEFINER RPCs above.
