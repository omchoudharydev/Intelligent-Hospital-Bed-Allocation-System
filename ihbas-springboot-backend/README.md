# IHBAS — Intelligent Hospital Bed Allocation System

Full-stack academic demonstration application built from the supplied IHBAS SRS and the MariaDB 10.4 schema. It contains a Spring Boot REST API and a responsive HTML/CSS/JavaScript frontend served by the same Spring Boot process.

## Included modules

### Admin
- Hospital overview KPIs, ward occupancy, admission trend, alerts and live bed map.
- Ward CRUD/deactivation and bed CRUD/status management.
- Admin/receptionist account creation and account enable/disable.
- Patient registry and searchable patient list.
- Allocation monitor and waiting list.
- Occupancy, average stay, average verification wait and cleaning turnaround metrics.
- CSV export and browser Print → Save as PDF.
- Searchable audit log, notifications and hospital profile settings.

### Receptionist
- Patient registration with duplicate check, generated UHID, linked PATIENT user account and receptionist-set initial password (no registration OTP).
- Searchable patient registry with patient details and create-admission flow.
- Upload/download patient identity, insurance, referral and consent documents (PDF/JPEG/PNG, 10 MB max) stored outside the public web root with SHA-256 checksum metadata.
- Admission request creation, patient online-request verification and ESI priority queue.
- Bed recommendation using ward match, facilities, patient compatibility, doctor-ward assignment and idle time.
- Transactional allocation, alternative ward handling and waiting list.
- Two-hour bed reservations and scheduled expiry; reservation confirmation.
- Patient transfer, discharge and housekeeping (Cleaning → Available).
- Discharge-time bed invoice generation, offline payment recording, Razorpay Checkout collection and CSV export.
- Notifications and active admission list.

### Patient
- Patient login using numeric User ID, generated username or email, with receptionist-issued initial password; no OTP is required to create an account.
- Patient profile, online admission request (untriaged ESI-5 until staff verification), active admission, history, bed availability, invoices and notifications.
- OTP-based password reset remains available separately.

### Security / data integrity
- BCrypt password hashing (strength 12), JWT bearer authentication and role-based API protection.
- Five failed password attempts trigger a 15-minute account lock.
- JWT expiry defaults to 30 minutes.
- Bed allocation runs in a transaction and locks the selected bed row (`SELECT ... FOR UPDATE`). The schema's generated active-allocation unique keys provide an additional database-level duplicate guard.
- Transfer, discharge, housekeeping, reservation expiry, audit events and in-app notifications are transactionally recorded where applicable.
- Patient/attendant data-processing consent is captured during registration. Patient account creation and patient record linking occur in one database transaction; initial passwords are BCrypt-hashed and never stored in plain text.

## Technology
- JDK 17+ (compiler bytecode target 17; compatible with JDK 26)
- Spring Boot 3.4.5, Spring Web, JDBC, Security, Validation
- MariaDB 10.4.x (XAMPP)
- JJWT 0.12.6, BCrypt
- HTML5, CSS3, vanilla JavaScript

## Important integration notes

1. This project targets the schema in `database/ihbas_database_xampp_mariadb_10_4.sql`. The script defines 31 tables and 4 views and seeds the 300-bed / 500-patient synthetic demo dataset described by the SRS.
2. If `ihbas_db` is already populated and working, **do not re-import the SQL just to run this application**. Back up the database before any schema operation. The script uses `CREATE TABLE IF NOT EXISTS`, but it is not a schema migration engine and it does not modify columns in already-existing tables.
3. The previous ADMIN account in the existing database is preserved. Do not rerun the bootstrap flow when an ADMIN account already exists.
4. Patient registration does not use OTP. Receptionist creates the patient record and account together, then shares the generated numeric User ID (or generated username) and initial password with the patient. OTP remains configured only for the separate forgotten-password reset flow.
5. Razorpay Standard Checkout is wired in test mode by default. The backend creates orders from the invoice's current outstanding balance, verifies Checkout HMAC signatures, fetches the payment from Razorpay and only credits captured payments. The webhook endpoint independently verifies `X-Razorpay-Signature` against the exact raw request body and de-duplicates events.
6. Both fresh databases (after the base schema import) and existing databases require the one-time migration `database/migrations/2026-11-01-payment-gateways.sql`. Back up `ihbas_db` first. This migration adds gateway order/event tables and the `RAZORPAY` payment method; it does not drop or recreate patient, staff, bed or invoice data.
7. Razorpay/MSG91 credentials are environment variables. For local development, copy `.env.example` to `.env` and replace the placeholders. Spring loads `.env`; the file is ignored by Git. Keep test-mode enabled and use `rzp_test_` credentials. Do not paste secrets into source files or commit `.env`.
8. MSG91 is optional and is used only for the separate forgotten-password OTP flow; patient account creation at reception does not call MSG91. To demonstrate password reset with SMS, configure an approved OTP Flow template, Authkey and applicable Indian DLT/sender settings. Razorpay webhooks require a publicly reachable HTTPS URL registered as `https://YOUR_HOST/api/webhooks/razorpay`; localhost by itself cannot receive provider webhooks.
9. CSV reports are generated by the backend. PDF is provided through the browser's Print → Save as PDF feature.
10. Patient documents can be uploaded and downloaded by authorized receptionist/patient routes. The current storage is private local disk, not cloud object storage; production requires encrypted storage, antivirus scanning, backup and retention controls.
11. This remains an academic single-hospital deployment. Do not use real patient/payment data or deploy publicly without HTTPS, a private JWT secret, private file storage, database backup/restore tests, security review and hospital approval.

## Run on Windows 10 + XAMPP

### 1. Start database
- Open XAMPP Control Panel.
- Start **MySQL** (MariaDB).
- Confirm database `ihbas_db` exists and contains the supplied schema.

For a new/empty setup only, import `database/ihbas_database_xampp_mariadb_10_4.sql` through phpMyAdmin. Do not import it over an existing production-like database without a backup and schema review.

### 2. Confirm Java and Maven
PowerShell:
```powershell
java -version
mvn -version
```
The SRS requires JDK 17 or newer. The project compiles to Java release 17, so it runs on JDK 17+ (including JDK 26) and does not require the JDK to match the build machine exactly.

If `mvn` is not on PATH, the provided PowerShell scripts also check the known local Maven path:
`C:\Tools\apache-maven\apache-maven-3.10.0\bin`.

### 3. Configure gateways (test mode)
1. Back up `ihbas_db`.
2. Open phpMyAdmin, select `ihbas_db`, and import `database/migrations/2026-11-01-payment-gateways.sql` once.
3. Run `Copy-Item .env.example .env` in PowerShell. Add the Razorpay Test Mode Key ID/Key Secret from the Razorpay dashboard and a separate webhook secret. Keep `RAZORPAY_TEST_MODE=true` and set `RAZORPAY_ENABLED=true`.
4. In Razorpay Test Mode, register the webhook URL `https://YOUR_PUBLIC_HTTPS_HOST/api/webhooks/razorpay` and subscribe to `payment.captured`, `payment.authorized`, and `payment.failed`. Use the exact same webhook secret in `.env`. Enable automatic payment capture in the Razorpay Dashboard so authorized payments transition to `captured`.
5. Optional: only when demonstrating the separate Forgot Password flow, create/approve an MSG91 OTP Flow template with a placeholder matching `MSG91_OTP_VARIABLE` (default `OTP`), complete applicable DLT/sender setup, then set `MSG91_AUTHKEY`, `MSG91_OTP_TEMPLATE_ID`, and `MSG91_ENABLED=true` in `.env`. This is not needed for patient registration or login.

The API credentials must remain on the server. `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, and `MSG91_AUTHKEY` are never sent to browser JavaScript. Do not set `OTP_DEV_EXPOSE_CODE=true` when MSG91 is enabled.

### 4. Start the application
From the project root:
```powershell
.\scripts\start-ihbas.ps1
```
Or run manually:
```powershell
$env:DB_URL = 'jdbc:mariadb://localhost:3306/ihbas_db'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = ''
mvn clean spring-boot:run
```
Keep this terminal open while using the application.

### 5. Open the frontend
- URL: `http://localhost:8080`
- Health: `http://localhost:8080/api/health`
- Admin login: use the ADMIN username/password already created in your database.
- Create receptionist accounts from Admin → Staff Management.
- Receptionist: open Register Patient, complete the patient details, set and confirm the initial password, capture consent, and submit. The confirmation displays the numeric Patient User ID, generated username, UHID and the one-time initial-password display.
- Patient: sign in using the numeric User ID (or generated username) and the password issued by reception. Existing patient records without an account can be linked from Search Patients → View / Admit → Create patient login.

### 6. Optional first-admin bootstrap (fresh database only)
Do this only when the database has no ADMIN account. Open a new PowerShell terminal in the project root:
```powershell
$env:APP_BOOTSTRAP_ADMIN_ENABLED = 'true'
$env:IHBAS_ADMIN_USERNAME = 'admin'
$env:IHBAS_ADMIN_FULL_NAME = 'Hospital Administrator'
$env:IHBAS_ADMIN_EMAIL = 'admin@example.invalid'
$env:IHBAS_ADMIN_PASSWORD = 'Use-A-Unique-Password-Here'
.\scripts\start-ihbas.ps1
```
After the first ADMIN is created, stop the server and clear the variables:
```powershell
Remove-Item Env:APP_BOOTSTRAP_ADMIN_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:IHBAS_ADMIN_PASSWORD -ErrorAction SilentlyContinue
Remove-Item Env:IHBAS_ADMIN_USERNAME -ErrorAction SilentlyContinue
Remove-Item Env:IHBAS_ADMIN_FULL_NAME -ErrorAction SilentlyContinue
Remove-Item Env:IHBAS_ADMIN_EMAIL -ErrorAction SilentlyContinue
```
The bootstrap runner skips creation if any ADMIN already exists.

## Configuration
Settings can be supplied as environment variables; never commit production secrets.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:mariadb://localhost:3306/ihbas_db` | MariaDB JDBC URL |
| `DB_USERNAME` | `root` | Database user |
| `DB_PASSWORD` | empty | Database password |
| `SERVER_PORT` | `8080` | HTTP port |
| `APP_JWT_SECRET` | development-only fallback | Base64 secret, at least 32 decoded bytes |
| `JWT_EXPIRATION_MINUTES` | `30` | JWT expiry |
| `OTP_DEV_EXPOSE_CODE` | `false` | Explicit local-only OTP exposure; ignored when MSG91 is enabled |
| `OTP_EXPIRY_MINUTES` | `5` | OTP lifetime |
| `RAZORPAY_ENABLED` | `false` | Enable Razorpay order creation |
| `RAZORPAY_TEST_MODE` | `true` | Reject non-test Razorpay keys while true |
| `RAZORPAY_KEY_ID` | empty | Razorpay test API key ID |
| `RAZORPAY_KEY_SECRET` | empty | Razorpay test API secret (server only) |
| `RAZORPAY_WEBHOOK_SECRET` | empty | Secret configured on the Razorpay webhook endpoint |
| `RAZORPAY_API_BASE_URL` | `https://api.razorpay.com/v1` | Razorpay API base URL |
| `MSG91_ENABLED` | `false` | Enable MSG91 OTP delivery |
| `MSG91_AUTHKEY` | empty | MSG91 Authkey (server only) |
| `MSG91_OTP_TEMPLATE_ID` | empty | MSG91 approved Flow template ID |
| `MSG91_OTP_VARIABLE` | `OTP` | Template variable name containing the one-time code |
| `MSG91_DEFAULT_COUNTRY_CODE` | `91` | Country code added to 10-digit Indian mobile numbers |
| `MSG91_FLOW_ENDPOINT` | MSG91 Flow API | Provider endpoint; override only when MSG91 instructs you to |
| `APP_BOOTSTRAP_ADMIN_ENABLED` | `false` | One-time admin bootstrap toggle |
| `CORS_ALLOWED_ORIGINS` | localhost origins | Comma-separated allowed browser origins |

## API summary

### Public authentication
- `POST /api/auth/login`
- `POST /api/auth/password/otp/request`
- `POST /api/auth/password/reset`
- `GET /api/health`

### Authenticated common endpoints
- `GET /api/auth/me`, `POST /api/auth/logout`
- `GET /api/wards`, `GET /api/doctors`
- `GET /api/beds/availability`, `GET /api/bed-board`
- `GET /api/notifications/unread-count`

### Admin (`ROLE_ADMIN`)
- `GET /api/admin/stats` and `GET /api/dashboard/admin`
- `GET/POST/PUT/DELETE /api/admin/wards`
- `GET/POST/PUT /api/admin/beds`, `PATCH /api/admin/beds/{id}/status`
- `GET/POST /api/admin/staff`, `PATCH /api/admin/staff/{id}/status`
- `GET /api/admin/patients`, `GET /api/admin/allocations`, `GET /api/admin/waiting-list`
- `GET /api/admin/reports/occupancy`, `GET /api/admin/reports/occupancy.csv`
- `GET /api/admin/audit`, `GET/PATCH /api/admin/notifications`, `GET/PUT /api/admin/settings`

### Receptionist (`ROLE_ADMIN` or `ROLE_RECEPTIONIST`)
- `GET /api/receptionist/stats`
- `GET/POST /api/receptionist/patients`, `GET /api/receptionist/patients/{id}`, `POST /api/receptionist/patients/{id}/account` (for existing patients without a linked user account)
- `GET /api/receptionist/requests/queue`, `POST /api/receptionist/requests`
- `POST /api/receptionist/requests/{id}/verify`, `GET /api/receptionist/requests/{id}/suggestions`
- `POST /api/receptionist/requests/{id}/allocate`, `POST /api/receptionist/requests/{id}/waitlist`
- `GET /api/receptionist/waiting-list`, `GET /api/receptionist/admissions/active`
- `PUT /api/receptionist/allocations/{id}/transfer`, `PUT /api/receptionist/allocations/{id}/discharge`
- `POST /api/receptionist/beds/{id}/clean`
- `POST /api/receptionist/reservations`, `POST /api/receptionist/reservations/{id}/confirm`
- `GET /api/receptionist/billing/invoices`, `POST /api/receptionist/billing/invoices/{id}/payments` (offline)
- `POST /api/receptionist/billing/razorpay/orders`, `POST /api/receptionist/billing/razorpay/verify`
- `GET /api/receptionist/billing/discharge-summary/{allocationId}`

### Public provider webhook
- `POST /api/webhooks/razorpay` — Razorpay payment events; signature required

### Patient (`ROLE_PATIENT`)
- `GET /api/patient/profile`, `GET /api/patient/admission`
- `POST /api/patient/admission-requests`
- `GET /api/patient/history`, `GET /api/patient/bills`, `GET /api/patient/bills/{id}`
- `GET /api/patient/bed-availability`, `GET /api/patient/notifications`
- `GET /api/patient/documents`, `GET /api/patient/documents/{documentId}/download`
- `POST /api/patient/bills/{invoiceId}/razorpay/orders`, `POST /api/patient/bills/razorpay/verify` (only the authenticated invoice owner can start/verify payment)

For protected APIs send:
`Authorization: Bearer <accessToken>`

## Build and local checks
```powershell
node --check .\src\main\resources\static\assets\js\app.js
.\scripts\build-ihbas.ps1
```
The JavaScript syntax check must complete before the Maven build. The executable jar is created under `target/`.

### Bed cleaning and availability workflow
- Discharge or transfer changes the old occupied bed to `CLEANING` and records the reason/user in bed history.
- A receptionist (or administrator) uses the housekeeping action **Mark clean & available**. It calls `POST /api/receptionist/beds/{id}/clean`.
- The backend locks the bed row, confirms the status is `CLEANING`, confirms no active allocation/bed-history row remains, changes it to `AVAILABLE`, writes bed history and audit data, then notifies reception to review the waiting list.
- Administrators cannot bypass housekeeping by using `PATCH /api/admin/beds/{id}/status` to change `CLEANING` directly to `AVAILABLE`. Maintenance/out-of-service restoration remains an administrator action.

The bed board is restricted to Admin and Receptionist because it includes patient-identifying admission details. Patient accounts only receive the read-only ward availability summary.

### Verification limits
A successful JavaScript syntax check does not prove database/runtime correctness. Run the Maven build and the workflow against a backup or synthetic database before using the system. Verify at least: Admin and Receptionist housekeeping permissions; patient access denied to `/api/bed-board`; discharge → cleaning → mark clean → available; maintenance restoration; rejected release while an active allocation exists; transfer to a bed that meets the original admission's oxygen/ventilator/monitor/isolation requirements; and simultaneous allocation attempts. Do not use real patient records for academic testing.


## Database compatibility fixes (v6)

- Qualified ward columns in the admin overview aggregate query to avoid ambiguous `ward_id` errors when joining `wards` and `beds`.
- Corrected patient bill listing/detail queries to join `invoices` for `invoice_date`, `due_date`, and `currency`; these columns are not exposed by `v_invoice_balances`.
- Receptionist billing query also joins `invoices` for date fields.
- No database reset or schema re-import is required for these query fixes.


## Database compatibility fixes (v7)
- Receptionist invoice list/detail queries read `invoice_date` and `due_date` from `invoices`, not from `v_invoice_balances` (the view does not expose those fields).
- Admin ward aggregation groups by every selected ward column for MariaDB SQL modes that enforce full grouping.
- Desktop sidebar can be collapsed/expanded from the top bar; the mobile navigation drawer remains separate.
- Existing database contents are preserved. Do not re-import the original schema over an existing installation.

If an endpoint still returns HTTP 500, inspect the Spring Boot console for the MariaDB error and compare the live schema with `database/ihbas_database_xampp_mariadb_10_4.sql`. This package cannot guarantee compatibility with a database that has been manually altered from the supplied schema.

## Admission queue lifecycle and retention (v9 update)

- The **Receptionist** owns day-to-day admission queue operations: verify triage, suggest/allocate a bed, move a verified request to the waiting list, or cancel an unallocated request with a mandatory reason.
- The **Admin** oversees the waiting list and can cancel an unallocated request from the Admin Waiting List page with a mandatory reason.
- Cancellation is a **soft state transition** (`admission_requests.status = 'CANCELLED'`; corresponding active waiting-list row becomes `CANCELLED`). It is not a physical DELETE.
- Allocated requests cannot be cancelled from the queue. They must follow the discharge workflow so bed status, allocation history, billing and discharge records remain consistent.
- This is intentional: the SRS integrity rules specify `ON DELETE RESTRICT` for clinical records and no hard delete of patients or allocations. Admission requests are referenced by allocations, waiting-list rows and reservations; deleting them would break traceability or fail due to foreign keys.
- Cancelled requests disappear from the live queue and live waiting list, but remain in the database and searchable audit history. No database cleanup/purge is run automatically.
