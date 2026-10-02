# NIQOB - Parental Digital Safety & Learning Platform

NIQOB is a child-safe digital environment and parental supervision platform designed to gradually cultivate healthy digital habits and independence.

---

## Architecture Overview

NIQOB Cloud is engineered to be platform-independent, supporting both Android and future iOS clients for both Parent and Child roles:

```
                    NIQOB CLOUD (Supabase / PostgreSQL)
                                    |
          +-------------------------+-------------------------+
          |                                                   |
     Parent APIs                                         Child APIs
(Auth, Sessions, Devices)                           (Pairing, Heartbeats)
          |                                                   |
   Android / iOS Parent App                            Android / iOS Child App
```

### Android Client Layering (Clean Architecture + MVVM)

```
UI Layer (Jetpack Compose + Material 3)
  ├── WelcomeScreen, LoginScreen, RegisterScreen
  ├── ParentDashboardScreen, DevicePairingScreen, ManageChildScreen
  └── ChildHomeScreen, ChildPairingScreen
       ↓
ViewModel Layer
  ├── AuthViewModel, ParentDashboardViewModel, ParentPairingViewModel, ChildPairingViewModel
       ↓
Domain Layer (Pure Kotlin, Platform-Independent)
  ├── Model: User, Family, Device, PairingSession, DeviceConnectivityStatus
  ├── Repository: AuthRepository, PairingRepository, DeviceRepository, NiqobRepository
  └── UseCase: ParentRegisterUseCase, ParentLoginUseCase, ParentLogoutUseCase,
               CreatePairingCodeUseCase, ConnectChildDeviceUseCase, RegisterDeviceUseCase,
               UpdateHeartbeatUseCase, GetDeviceConnectivityUseCase
       ↓
Data Layer
  ├── Remote (Supabase / REST / RPC / Auth):
  │     ├── SupabaseConfig (Safe mobile client config)
  │     ├── SupabaseHttpClient & DefaultSupabaseHttpClient
  │     ├── SupabaseRemoteDataSource
  │     └── DTOs: AuthDtos, DeviceDto, PairingSessionDto, RpcDtos
  └── Repository Implementation:
        ├── AuthRepositoryImpl (Auth state flow, session persistence)
        ├── SupabasePairingRepositoryImpl (Cloud pairing with local fallback)
        └── DeviceRepositoryImpl (Device registration & heartbeat)
```

---

## Supabase Setup & Configuration

### 1. Database Schema Execution
The complete PostgreSQL schema, Row Level Security (RLS) policies, triggers, and secure stored procedures are provided in [`supabase/schema.sql`](supabase/schema.sql).

To initialize your Supabase project:
1. Open your [Supabase Dashboard](https://supabase.com/dashboard).
2. Create a new project named `niqob-cloud`.
3. In the left navigation, go to **SQL Editor**.
4. Paste the entire content of [`supabase/schema.sql`](supabase/schema.sql) and click **Run**.

### 2. Local Environment Configuration
To link your Android app to your Supabase project:

1. Open your project's `local.properties` file (located in the project root; this file is ignored by Git):
   ```properties
   SUPABASE_URL=https://<your-project-id>.supabase.co
   SUPABASE_ANON_KEY=<your-public-anon-key>
   ```
2. **SECURITY NOTICE**:
   - **Never** put the `service_role` secret key into `local.properties` or anywhere in the Android app.
   - Only supply the **public `anon` key** from **Project Settings → API**.
   - If `SUPABASE_URL` and `SUPABASE_ANON_KEY` are not set, the app automatically runs in a local mock simulation mode for offline development and testing.

---

## Database Schema & Models

### 1. `profiles`
Links user profiles directly to Supabase Auth (`auth.users`).
- `id` (UUID, Primary Key, references `auth.users(id) ON DELETE CASCADE`)
- `role` (TEXT, check constraint `IN ('PARENT', 'CHILD')`)
- `display_name` (TEXT)
- `created_at` (TIMESTAMPTZ)
- `updated_at` (TIMESTAMPTZ)

### 2. `families`
Groups parents and paired devices.
- `id` (UUID, Primary Key, `gen_random_uuid()`)
- `parent_id` (UUID, references `auth.users(id) ON DELETE CASCADE`)
- `created_at` (TIMESTAMPTZ)
- `updated_at` (TIMESTAMPTZ)

### 3. `devices`
Tracks registered hardware devices with minimal connectivity telemetry.
- `id` (UUID, Primary Key, `gen_random_uuid()`)
- `family_id` (UUID, references `families(id) ON DELETE CASCADE`)
- `user_id` (UUID, references `auth.users(id) ON DELETE SET NULL`)
- `device_name` (TEXT)
- `platform` (TEXT, check constraint `IN ('ANDROID', 'IOS')`)
- `device_type` (TEXT, check constraint `IN ('PARENT_PHONE', 'CHILD_PHONE', 'CHILD_TABLET')`)
- `last_seen_at` (TIMESTAMPTZ)
- `created_at` (TIMESTAMPTZ)
- `updated_at` (TIMESTAMPTZ)

### 4. `pairing_sessions`
Tracks short-lived pairing codes and redemption state.
- `id` (UUID, Primary Key, `gen_random_uuid()`)
- `family_id` (UUID, references `families(id) ON DELETE CASCADE`)
- `parent_device_id` (TEXT)
- `child_device_id` (TEXT, nullable)
- `pairing_code` (TEXT)
- `status` (TEXT, check constraint `IN ('PENDING', 'CONNECTED', 'EXPIRED', 'REVOKED')`)
- `expires_at` (TIMESTAMPTZ)
- `created_at` (TIMESTAMPTZ)
- `connected_at` (TIMESTAMPTZ, nullable)
- `revoked_at` (TIMESTAMPTZ, nullable)

---

## Security Model & Row Level Security (RLS)

- **Row Level Security (RLS)** is enabled on all tables: `profiles`, `families`, `devices`, and `pairing_sessions`.
- **Strict Isolation**:
  - Parents can only select, update, and manage records belonging to their own family (`WHERE parent_id = auth.uid()`).
  - No open read-all or write-all policies exist.
- **Server-Side Validation via `SECURITY DEFINER` RPC Functions**:
  - `create_pairing_session(p_parent_device_id)`: Generates a cryptographically random 6-digit code with a strict 10-minute expiration.
  - `pair_child_device(p_pairing_code, p_device_name, p_platform, p_device_type)`:
    - Atomically validates pairing codes on the server.
    - Rejects expired codes and marks them `EXPIRED`.
    - Rejects already connected or revoked codes.
    - Atomically inserts the new child device and transitions the session to `CONNECTED`.
    - Prevents exposing sensitive database queries to unauthenticated devices.
  - `update_device_heartbeat(p_device_id)`: Atomically updates `last_seen_at = NOW()`.
  - `revoke_pairing_session(p_session_id)`: Allows parents to revoke active sessions immediately.

---

## Connectivity & Heartbeat Thresholds

The parent dashboard evaluates connectivity status from the child's last heartbeat:
- **`ONLINE`**: Heartbeat received within the last 2 minutes (`<= 120,000 ms`).
- **`RECENTLY_ONLINE`**: Heartbeat received between 2 minutes and 15 minutes ago (`<= 900,000 ms`).
- **`OFFLINE`**: No heartbeat received for more than 15 minutes (`> 900,000 ms`), or device has never been seen.

*Note: This is strictly a connectivity signal. NIQOB does not monitor private messages, keystrokes, passwords, or personal content.*

---

## How to Run Locally

### Prerequisites
- JDK 17
- Android SDK (API 35, Min SDK 26)

### Running the App
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew installDebug
```

### Running Unit Tests
All unit tests run locally without requiring an active Supabase internet connection (using test-safe repository and HTTP abstractions):
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew test
```

### Running Lint & Static Analysis
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH" \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew lintDebug
```
