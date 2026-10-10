-- ==============================================================================
-- NIQOB CLOUD MIGRATION: CRYPTOGRAPHIC CHILD DEVICE SECRET FOUNDATION (PHASE 5 STEP 5-2)
--
-- Objective:
--   Establish cryptographic child-device credential authentication foundation.
--   Replaces device_id-as-sole-authenticator with a high-entropy secret token.
--
-- Architectural Changes:
--   1. Column: public.devices.device_secret_hash TEXT (NULLABLE)
--      - Stores ONLY the SHA-256 digest (64-char lowercase hex) of the raw secret.
--      - Nullable to maintain non-breaking backward compatibility with existing paired devices.
--      - The raw secret is NEVER persisted in PostgreSQL.
--   2. RPC: public.pair_child_device(TEXT, TEXT, TEXT, TEXT)
--      - Generates 32 bytes of cryptographic entropy via gen_random_bytes(32) (256 bits).
--      - Encodes raw secret as 64-char lowercase hex.
--      - Computes SHA-256 hash using pgcrypto digest(..., 'sha256').
--      - Stores ONLY the 64-char hex hash into devices.device_secret_hash.
--      - Returns the raw 64-char hex secret in the JSON response exactly once upon successful pairing.
--      - Preserves all existing validations, concurrency locking (FOR UPDATE), family isolation,
--        and error handling.
--
-- Security Controls:
--   - Raw secret is transient in PL/pgSQL variable memory and never written to tables or logs.
--   - Stored credential is a one-way cryptographic hash (SHA-256).
--   - SECURITY DEFINER execution model preserved with locked search_path = public, extensions, pg_temp.
--   - Least-privilege execution grants: PUBLIC revoked, granted strictly to anon and authenticated.
--   - No child RPC authorization enforcement modified in this step (deferred to subsequent Phase 5 steps).
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. Ensure Required Extensions Exist
-- ------------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ------------------------------------------------------------------------------
-- 2. Add Nullable device_secret_hash Column to public.devices
-- ------------------------------------------------------------------------------
ALTER TABLE public.devices
    ADD COLUMN IF NOT EXISTS device_secret_hash TEXT;

COMMENT ON COLUMN public.devices.device_secret_hash IS
    'Cryptographic child-device credential: stores SHA-256 hash (64-char lowercase hex) of the 256-bit random device secret. The raw secret is returned to the child device exactly once during pair_child_device and never persisted. Legacy devices may have NULL until re-paired; strict verification will be enforced in a later Phase 5 step.';

-- ------------------------------------------------------------------------------
-- 3. Update RPC: public.pair_child_device
-- ------------------------------------------------------------------------------
-- Server-side validation of pairing code with row-level locking (FOR UPDATE)
-- to prevent concurrent race conditions. Registers child device, generates
-- 256-bit cryptographic secret, stores SHA-256 hash, marks session CONNECTED,
-- and returns the raw secret to the child device exactly once.
-- ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.pair_child_device(
    p_pairing_code TEXT,
    p_device_name TEXT,
    p_platform TEXT DEFAULT 'ANDROID',
    p_device_type TEXT DEFAULT 'CHILD_PHONE'
)
RETURNS JSONB AS $$
DECLARE
    v_session RECORD;
    v_device_id UUID;
    v_now TIMESTAMPTZ := NOW();
    v_existing_status TEXT;
    v_device_secret TEXT;
    v_device_secret_hash TEXT;
BEGIN
    p_pairing_code := trim(p_pairing_code);

    -- Strict format check before querying
    IF length(p_pairing_code) != 6 OR p_pairing_code !~ '^\d{6}$' THEN
        RETURN jsonb_build_object(
            'success', false,
            'error_code', 'INVALID_CODE',
            'message', 'Pairing code must be a 6-digit number.'
        );
    END IF;

    -- Search strictly for active, non-expired pending session.
    -- Row-level lock (FOR UPDATE) serializes redemption and prevents race conditions.
    SELECT * INTO v_session
    FROM public.pairing_sessions
    WHERE pairing_code = p_pairing_code
      AND status = 'PENDING'
      AND expires_at > v_now
    ORDER BY created_at DESC
    LIMIT 1
    FOR UPDATE;

    IF v_session IS NULL THEN
        -- Check if an older session with this code was REVOKED, CONNECTED, or EXPIRED
        SELECT status INTO v_existing_status
        FROM public.pairing_sessions
        WHERE pairing_code = p_pairing_code
        ORDER BY created_at DESC
        LIMIT 1;

        IF v_existing_status = 'REVOKED' THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'REVOKED',
                'message', 'This pairing code has been revoked by the parent.'
            );
        ELSIF v_existing_status = 'CONNECTED' THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'ALREADY_USED',
                'message', 'This pairing code has already been used.'
            );
        ELSIF v_existing_status = 'EXPIRED' THEN
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'EXPIRED',
                'message', 'This pairing code has expired. Please generate a new code on the parent device.'
            );
        ELSE
            RETURN jsonb_build_object(
                'success', false,
                'error_code', 'INVALID_CODE',
                'message', 'Invalid pairing code. Please verify the code shown on the parent device.'
            );
        END IF;
    END IF;

    -- Generate a cryptographically random 32-byte secret (256 bits of entropy)
    -- and compute its SHA-256 hash. Only the hash is stored in the database.
    -- The raw secret is returned to the child device exactly once.
    v_device_secret := encode(gen_random_bytes(32), 'hex');
    v_device_secret_hash := encode(
        digest(decode(v_device_secret, 'hex'), 'sha256'),
        'hex'
    );

    -- Code is valid and pending. Register child device into family.
    INSERT INTO public.devices (
        family_id,
        device_name,
        platform,
        device_type,
        last_seen_at,
        device_secret_hash
    ) VALUES (
        v_session.family_id,
        COALESCE(NULLIF(trim(p_device_name), ''), 'Child Device'),
        COALESCE(NULLIF(trim(p_platform), ''), 'ANDROID'),
        COALESCE(NULLIF(trim(p_device_type), ''), 'CHILD_PHONE'),
        v_now,
        v_device_secret_hash
    )
    RETURNING id INTO v_device_id;

    -- Update session to CONNECTED
    UPDATE public.pairing_sessions
    SET status = 'CONNECTED',
        child_device_id = v_device_id::text,
        connected_at = v_now
    WHERE id = v_session.id;

    RETURN jsonb_build_object(
        'success', true,
        'session_id', v_session.id,
        'family_id', v_session.family_id,
        'device_id', v_device_id,
        'device_name', COALESCE(NULLIF(trim(p_device_name), ''), 'Child Device'),
        'status', 'CONNECTED',
        'connected_at', v_now,
        'device_secret', v_device_secret
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions, pg_temp;

-- ------------------------------------------------------------------------------
-- 4. Execution Privileges (Principle of Least Privilege)
-- ------------------------------------------------------------------------------
-- Revoke default public execution privileges
REVOKE EXECUTE ON FUNCTION public.pair_child_device(TEXT, TEXT, TEXT, TEXT) FROM PUBLIC;

-- Grant execution privileges strictly to anon (child device) and authenticated (parents/admin)
GRANT EXECUTE ON FUNCTION public.pair_child_device(TEXT, TEXT, TEXT, TEXT) TO anon, authenticated;
