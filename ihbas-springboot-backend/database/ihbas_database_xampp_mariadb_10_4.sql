-- ============================================================================
-- IHBAS: Intelligent Hospital Bed Allocation System
-- Database: XAMPP MariaDB 10.4.32 compatible
-- Target: XAMPP MariaDB 10.4.32; single-hospital deployment; Java 17 + JDBC
-- User-approved decisions: Receptionist handles housekeeping; full internal
-- billing ledger; SMS OTP; senior-citizen threshold = 60 years.
-- IMPORTANT: Use synthetic data for academic demonstrations. No real patient
-- data should be loaded until privacy, retention, backup and access policies
-- are approved by the hospital.
-- ============================================================================

USE railway;
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ------------------------------ CONFIGURATION -------------------------------
CREATE TABLE IF NOT EXISTS hospital_profile (
  hospital_id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  hospital_code VARCHAR(30) NOT NULL UNIQUE,
  hospital_name VARCHAR(160) NOT NULL,
  timezone_name VARCHAR(64) NOT NULL DEFAULT 'Asia/Kolkata',
  phone VARCHAR(20) NULL,
  email VARCHAR(160) NULL,
  address TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_single_hospital CHECK (hospital_id = 1)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS hospital_settings (
  setting_key VARCHAR(100) NOT NULL PRIMARY KEY,
  setting_value VARCHAR(1000) NOT NULL,
  description VARCHAR(255) NULL,
  updated_by BIGINT UNSIGNED NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ------------------------------- IDENTITY -----------------------------------
CREATE TABLE IF NOT EXISTS users (
  user_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  email VARCHAR(160) NULL UNIQUE,
  phone VARCHAR(20) NULL,
  full_name VARCHAR(150) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role ENUM('ADMIN','RECEPTIONIST','PATIENT') NOT NULL,
  account_status ENUM('ACTIVE','LOCKED','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  failed_login_attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  locked_until DATETIME NULL,
  last_login_at DATETIME NULL,
  password_changed_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_user_identifier CHECK (email IS NOT NULL OR phone IS NOT NULL OR username <> ''),
  INDEX idx_users_role_status (role, account_status),
  INDEX idx_users_phone (phone)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS user_sessions (
  session_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  jti_hash CHAR(64) NOT NULL UNIQUE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_activity_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  idle_expires_at DATETIME NOT NULL,
  absolute_expires_at DATETIME NOT NULL,
  revoked_at DATETIME NULL,
  revoke_reason VARCHAR(120) NULL,
  ip_address VARCHAR(45) NULL,
  user_agent VARCHAR(500) NULL,
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
  INDEX idx_sessions_user_active (user_id, revoked_at, idle_expires_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS otp_verifications (
  otp_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  phone VARCHAR(20) NOT NULL,
  purpose ENUM('PATIENT_REGISTRATION','LOGIN_VERIFICATION','PASSWORD_RESET','PHONE_CHANGE') NOT NULL,
  otp_hash VARCHAR(255) NOT NULL,
  expires_at DATETIME NOT NULL,
  attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts TINYINT UNSIGNED NOT NULL DEFAULT 5,
  consumed_at DATETIME NULL,
  delivery_status ENUM('PENDING','SENT','DELIVERED','FAILED') NOT NULL DEFAULT 'PENDING',
  provider_message_id VARCHAR(180) NULL,
  sent_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  request_ip VARCHAR(45) NULL,
  INDEX idx_otp_phone_purpose (phone, purpose, created_at),
  INDEX idx_otp_expiry (expires_at, consumed_at),
  CONSTRAINT chk_otp_attempts CHECK (attempts <= max_attempts)
) ENGINE=InnoDB;

-- ---------------------------- ORGANIZATION ----------------------------------
CREATE TABLE IF NOT EXISTS departments (
  department_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  description VARCHAR(255) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS esi_levels (
  esi_level TINYINT UNSIGNED NOT NULL PRIMARY KEY,
  label VARCHAR(100) NOT NULL,
  description VARCHAR(255) NOT NULL,
  emergency_flag BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT chk_esi_level CHECK (esi_level BETWEEN 1 AND 5)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS wards (
  ward_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  ward_name VARCHAR(100) NOT NULL UNIQUE,
  ward_type VARCHAR(40) NOT NULL,
  floor_label VARCHAR(30) NULL,
  gender_policy ENUM('ANY','MALE_ONLY','FEMALE_ONLY') NOT NULL DEFAULT 'ANY',
  capacity SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  status ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_ward_capacity CHECK (capacity >= 0),
  INDEX idx_wards_type_status (ward_type, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS doctors (
  doctor_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  doctor_code VARCHAR(40) NOT NULL UNIQUE,
  full_name VARCHAR(150) NOT NULL,
  department_id BIGINT UNSIGNED NULL,
  phone VARCHAR(20) NULL,
  email VARCHAR(160) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_doctors_department FOREIGN KEY (department_id) REFERENCES departments(department_id) ON DELETE SET NULL,
  INDEX idx_doctors_name (full_name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS doctor_ward_assignments (
  doctor_id BIGINT UNSIGNED NOT NULL,
  ward_id BIGINT UNSIGNED NOT NULL,
  assigned_from DATE NOT NULL,
  assigned_until DATE NULL,
  PRIMARY KEY (doctor_id, ward_id, assigned_from),
  CONSTRAINT fk_dwa_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(doctor_id) ON DELETE RESTRICT,
  CONSTRAINT fk_dwa_ward FOREIGN KEY (ward_id) REFERENCES wards(ward_id) ON DELETE RESTRICT,
  CONSTRAINT chk_dwa_dates CHECK (assigned_until IS NULL OR assigned_until >= assigned_from)
) ENGINE=InnoDB;

-- ------------------------------- PATIENTS -----------------------------------
CREATE TABLE IF NOT EXISTS patients (
  patient_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NULL UNIQUE,
  uhid VARCHAR(30) NOT NULL UNIQUE,
  full_name VARCHAR(150) NOT NULL,
  date_of_birth DATE NOT NULL,
  gender ENUM('MALE','FEMALE','OTHER','UNDISCLOSED') NOT NULL,
  blood_group ENUM('A_POS','A_NEG','B_POS','B_NEG','AB_POS','AB_NEG','O_POS','O_NEG','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
  phone VARCHAR(20) NOT NULL,
  email VARCHAR(160) NULL,
  address TEXT NULL,
  emergency_contact_name VARCHAR(150) NULL,
  emergency_contact_phone VARCHAR(20) NULL,
  id_proof_type ENUM('AADHAAR','ABHA','PASSPORT','VOTER_ID','OTHER','NONE') NOT NULL DEFAULT 'NONE',
  id_proof_encrypted TEXT NULL,
  allergies TEXT NULL,
  chronic_conditions TEXT NULL,
  insurance_type ENUM('CASH','PM_JAY','PRIVATE','CORPORATE','OTHER') NOT NULL DEFAULT 'CASH',
  record_status ENUM('ACTIVE','INACTIVE','MERGED') NOT NULL DEFAULT 'ACTIVE',
  created_by BIGINT UNSIGNED NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_patients_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT fk_patients_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE SET NULL,
  -- Date-of-birth upper-bound validation is performed by the Java service;
  -- MySQL CHECK constraints cannot safely use the changing CURRENT_DATE value.
  INDEX idx_patients_name_dob (full_name, date_of_birth),
  INDEX idx_patients_phone (phone),
  INDEX idx_patients_status (record_status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS patient_consents (
  consent_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT UNSIGNED NOT NULL,
  consent_type VARCHAR(80) NOT NULL,
  consent_version VARCHAR(30) NOT NULL,
  granted BOOLEAN NOT NULL,
  granted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at DATETIME NULL,
  recorded_by BIGINT UNSIGNED NULL,
  notes VARCHAR(500) NULL,
  CONSTRAINT fk_consents_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id) ON DELETE RESTRICT,
  CONSTRAINT fk_consents_user FOREIGN KEY (recorded_by) REFERENCES users(user_id) ON DELETE SET NULL,
  INDEX idx_consents_patient_type (patient_id, consent_type, granted_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS patient_documents (
  document_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT UNSIGNED NOT NULL,
  document_type ENUM('IDENTITY','INSURANCE','REFERRAL','CONSENT','DISCHARGE_SUMMARY','OTHER') NOT NULL,
  storage_key VARCHAR(500) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  mime_type VARCHAR(120) NOT NULL,
  file_size_bytes BIGINT UNSIGNED NOT NULL,
  sha256_checksum CHAR(64) NOT NULL,
  uploaded_by BIGINT UNSIGNED NULL,
  uploaded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_documents_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id) ON DELETE RESTRICT,
  CONSTRAINT fk_documents_user FOREIGN KEY (uploaded_by) REFERENCES users(user_id) ON DELETE SET NULL,
  INDEX idx_documents_patient (patient_id, uploaded_at)
) ENGINE=InnoDB;

-- ---------------------------------- BEDS ------------------------------------
CREATE TABLE IF NOT EXISTS beds (
  bed_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  bed_code VARCHAR(30) NOT NULL UNIQUE,
  ward_id BIGINT UNSIGNED NOT NULL,
  category VARCHAR(40) NOT NULL,
  floor_label VARCHAR(30) NULL,
  daily_charge DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  has_oxygen BOOLEAN NOT NULL DEFAULT FALSE,
  has_ventilator BOOLEAN NOT NULL DEFAULT FALSE,
  has_monitor BOOLEAN NOT NULL DEFAULT FALSE,
  attached_bathroom BOOLEAN NOT NULL DEFAULT FALSE,
  isolation_capable BOOLEAN NOT NULL DEFAULT FALSE,
  status ENUM('AVAILABLE','OCCUPIED','RESERVED','CLEANING','MAINTENANCE','OUT_OF_SERVICE') NOT NULL DEFAULT 'AVAILABLE',
  notes VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_beds_ward FOREIGN KEY (ward_id) REFERENCES wards(ward_id) ON DELETE RESTRICT,
  CONSTRAINT chk_bed_charge CHECK (daily_charge >= 0),
  INDEX idx_beds_ward_status (ward_id, status),
  INDEX idx_beds_category_status (category, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS bed_status_history (
  bed_status_history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  bed_id BIGINT UNSIGNED NOT NULL,
  old_status ENUM('AVAILABLE','OCCUPIED','RESERVED','CLEANING','MAINTENANCE','OUT_OF_SERVICE') NULL,
  new_status ENUM('AVAILABLE','OCCUPIED','RESERVED','CLEANING','MAINTENANCE','OUT_OF_SERVICE') NOT NULL,
  reason VARCHAR(500) NULL,
  changed_by BIGINT UNSIGNED NULL,
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_bsh_bed FOREIGN KEY (bed_id) REFERENCES beds(bed_id) ON DELETE RESTRICT,
  CONSTRAINT fk_bsh_user FOREIGN KEY (changed_by) REFERENCES users(user_id) ON DELETE SET NULL,
  INDEX idx_bsh_bed_time (bed_id, changed_at)
) ENGINE=InnoDB;

-- -------------------------- ADMISSION / ALLOCATION --------------------------
CREATE TABLE IF NOT EXISTS admission_requests (
  request_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT UNSIGNED NOT NULL,
  requested_category VARCHAR(40) NOT NULL,
  esi_level TINYINT UNSIGNED NOT NULL,
  reason TEXT NOT NULL,
  doctor_id BIGINT UNSIGNED NULL,
  expected_stay_days SMALLINT UNSIGNED NULL,
  requires_oxygen BOOLEAN NOT NULL DEFAULT FALSE,
  requires_ventilator BOOLEAN NOT NULL DEFAULT FALSE,
  requires_monitor BOOLEAN NOT NULL DEFAULT FALSE,
  isolation_required BOOLEAN NOT NULL DEFAULT FALSE,
  clinical_isolation_verified BOOLEAN NOT NULL DEFAULT FALSE,
  status ENUM('PENDING_VERIFICATION','VERIFIED','WAITING','ALLOCATED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING_VERIFICATION',
  requested_by BIGINT UNSIGNED NOT NULL,
  verified_by BIGINT UNSIGNED NULL,
  verified_at DATETIME NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_requests_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id) ON DELETE RESTRICT,
  CONSTRAINT fk_requests_doctor FOREIGN KEY (doctor_id) REFERENCES doctors(doctor_id) ON DELETE SET NULL,
  CONSTRAINT fk_requests_creator FOREIGN KEY (requested_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT fk_requests_verifier FOREIGN KEY (verified_by) REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT chk_request_esi CHECK (esi_level BETWEEN 1 AND 5),
  CONSTRAINT fk_requests_esi FOREIGN KEY (esi_level) REFERENCES esi_levels(esi_level) ON DELETE RESTRICT,
  CONSTRAINT chk_request_stay CHECK (expected_stay_days IS NULL OR expected_stay_days > 0),
  INDEX idx_requests_queue (status, esi_level, requested_at),
  INDEX idx_requests_patient (patient_id, requested_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS waiting_list (
  wait_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  request_id BIGINT UNSIGNED NOT NULL UNIQUE,
  priority_score DECIMAL(8,2) NULL,
  added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status ENUM('WAITING','OFFERED','ALLOCATED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'WAITING',
  last_offered_at DATETIME NULL,
  CONSTRAINT fk_wait_request FOREIGN KEY (request_id) REFERENCES admission_requests(request_id) ON DELETE RESTRICT,
  INDEX idx_wait_order (status, added_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS allocations (
  allocation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  patient_id BIGINT UNSIGNED NOT NULL,
  bed_id BIGINT UNSIGNED NOT NULL,
  request_id BIGINT UNSIGNED NOT NULL UNIQUE,
  allocated_by BIGINT UNSIGNED NOT NULL,
  allocation_reason VARCHAR(1000) NULL,
  admit_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expected_discharge DATETIME NULL,
  discharge_time DATETIME NULL,
  discharge_type ENUM('RECOVERED','REFERRED','LAMA','DECEASED','OTHER') NULL,
  status ENUM('ACTIVE','DISCHARGED','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  active_patient_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN patient_id ELSE NULL END) STORED,
  active_bed_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN bed_id ELSE NULL END) STORED,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_alloc_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id) ON DELETE RESTRICT,
  CONSTRAINT fk_alloc_bed FOREIGN KEY (bed_id) REFERENCES beds(bed_id) ON DELETE RESTRICT,
  CONSTRAINT fk_alloc_request FOREIGN KEY (request_id) REFERENCES admission_requests(request_id) ON DELETE RESTRICT,
  CONSTRAINT fk_alloc_user FOREIGN KEY (allocated_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT chk_alloc_dates CHECK (expected_discharge IS NULL OR expected_discharge >= admit_time),
  CONSTRAINT chk_alloc_discharge CHECK ((status = 'ACTIVE' AND discharge_time IS NULL) OR (status <> 'ACTIVE' AND discharge_time IS NOT NULL) OR status = 'CANCELLED'),
  UNIQUE KEY uq_one_active_allocation_per_patient (active_patient_id),
  UNIQUE KEY uq_one_active_allocation_per_bed (active_bed_id),
  INDEX idx_alloc_status_admit (status, admit_time),
  INDEX idx_alloc_patient_history (patient_id, admit_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS allocation_bed_history (
  allocation_bed_history_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  allocation_id BIGINT UNSIGNED NOT NULL,
  bed_id BIGINT UNSIGNED NOT NULL,
  started_at DATETIME NOT NULL,
  ended_at DATETIME NULL,
  daily_charge_snapshot DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  active_bed_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN ended_at IS NULL THEN bed_id ELSE NULL END) STORED,
  CONSTRAINT fk_abh_allocation FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT,
  CONSTRAINT fk_abh_bed FOREIGN KEY (bed_id) REFERENCES beds(bed_id) ON DELETE RESTRICT,
  CONSTRAINT chk_abh_dates CHECK (ended_at IS NULL OR ended_at >= started_at),
  CONSTRAINT chk_abh_charge CHECK (daily_charge_snapshot >= 0),
  UNIQUE KEY uq_active_bed_history (active_bed_id),
  INDEX idx_abh_allocation_time (allocation_id, started_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS transfers (
  transfer_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  allocation_id BIGINT UNSIGNED NOT NULL,
  from_bed_id BIGINT UNSIGNED NOT NULL,
  to_bed_id BIGINT UNSIGNED NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  transferred_by BIGINT UNSIGNED NOT NULL,
  transfer_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_transfer_allocation FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT,
  CONSTRAINT fk_transfer_from_bed FOREIGN KEY (from_bed_id) REFERENCES beds(bed_id) ON DELETE RESTRICT,
  CONSTRAINT fk_transfer_to_bed FOREIGN KEY (to_bed_id) REFERENCES beds(bed_id) ON DELETE RESTRICT,
  CONSTRAINT fk_transfer_user FOREIGN KEY (transferred_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT chk_transfer_different_beds CHECK (from_bed_id <> to_bed_id),
  INDEX idx_transfer_allocation_time (allocation_id, transfer_time)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS reservations (
  reservation_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  bed_id BIGINT UNSIGNED NOT NULL,
  patient_id BIGINT UNSIGNED NOT NULL,
  request_id BIGINT UNSIGNED NOT NULL,
  reserved_by BIGINT UNSIGNED NOT NULL,
  reserved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  status ENUM('ACTIVE','CONFIRMED','CANCELLED','EXPIRED') NOT NULL DEFAULT 'ACTIVE',
  active_bed_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN bed_id ELSE NULL END) STORED,
  CONSTRAINT fk_res_bed FOREIGN KEY (bed_id) REFERENCES beds(bed_id) ON DELETE RESTRICT,
  CONSTRAINT fk_res_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id) ON DELETE RESTRICT,
  CONSTRAINT fk_res_request FOREIGN KEY (request_id) REFERENCES admission_requests(request_id) ON DELETE RESTRICT,
  CONSTRAINT fk_res_user FOREIGN KEY (reserved_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT chk_res_expiry CHECK (expires_at > reserved_at AND expires_at <= DATE_ADD(reserved_at, INTERVAL 2 HOUR)),
  UNIQUE KEY uq_active_reservation_per_bed (active_bed_id),
  INDEX idx_res_expiry (status, expires_at)
) ENGINE=InnoDB;

-- --------------------------- NOTIFICATIONS / AUDIT --------------------------
CREATE TABLE IF NOT EXISTS notifications (
  notification_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  title VARCHAR(180) NOT NULL,
  message VARCHAR(1500) NOT NULL,
  notification_type VARCHAR(60) NOT NULL,
  is_read BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  read_at DATETIME NULL,
  CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
  INDEX idx_notifications_user_read (user_id, is_read, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS notification_outbox (
  event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  event_type VARCHAR(80) NOT NULL,
  aggregate_type VARCHAR(80) NOT NULL,
  aggregate_id BIGINT UNSIGNED NOT NULL,
  payload JSON NOT NULL,
  status ENUM('PENDING','PROCESSING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
  retry_count SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at DATETIME NULL,
  last_error VARCHAR(1000) NULL,
  INDEX idx_outbox_dispatch (status, next_attempt_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS notification_deliveries (
  delivery_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT UNSIGNED NULL,
  event_id BIGINT UNSIGNED NULL,
  channel ENUM('SMS','IN_APP') NOT NULL,
  destination VARCHAR(180) NOT NULL,
  provider_message_id VARCHAR(180) NULL,
  status ENUM('PENDING','SENT','DELIVERED','FAILED') NOT NULL DEFAULT 'PENDING',
  attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  delivered_at DATETIME NULL,
  last_error VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications(notification_id) ON DELETE SET NULL,
  CONSTRAINT fk_delivery_event FOREIGN KEY (event_id) REFERENCES notification_outbox(event_id) ON DELETE SET NULL,
  INDEX idx_delivery_status (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS audit_log (
  audit_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NULL,
  action VARCHAR(100) NOT NULL,
  entity_type VARCHAR(80) NOT NULL,
  entity_id VARCHAR(80) NULL,
  details JSON NULL,
  ip_address VARCHAR(45) NULL,
  user_agent VARCHAR(500) NULL,
  event_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL,
  INDEX idx_audit_entity (entity_type, entity_id, event_time),
  INDEX idx_audit_user_time (user_id, event_time),
  INDEX idx_audit_event_time (event_time)
) ENGINE=InnoDB;

-- -------------------------------- BILLING -----------------------------------
-- Billing is an approved extension beyond the baseline SRS. This is an internal
-- inpatient billing ledger; no external insurer/payment provider is integrated.
CREATE TABLE IF NOT EXISTS charge_catalog (
  charge_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  charge_code VARCHAR(40) NOT NULL UNIQUE,
  description VARCHAR(255) NOT NULL,
  category ENUM('BED','DOCTOR','NURSING','PROCEDURE','SERVICE','OTHER') NOT NULL,
  unit_price DECIMAL(12,2) NOT NULL,
  billing_unit ENUM('PER_DAY','PER_VISIT','PER_ITEM','FLAT') NOT NULL DEFAULT 'PER_ITEM',
  tax_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT chk_charge_price CHECK (unit_price >= 0),
  CONSTRAINT chk_charge_tax CHECK (tax_rate BETWEEN 0 AND 100),
  INDEX idx_charge_category_active (category, active)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS invoices (
  invoice_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  invoice_no VARCHAR(40) NOT NULL UNIQUE,
  patient_id BIGINT UNSIGNED NOT NULL,
  allocation_id BIGINT UNSIGNED NULL,
  invoice_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  due_date DATETIME NULL,
  currency CHAR(3) NOT NULL DEFAULT 'INR',
  subtotal DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  discount_total DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  tax_total DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  total_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  status ENUM('DRAFT','ISSUED','PARTIALLY_PAID','PAID','VOID') NOT NULL DEFAULT 'DRAFT',
  notes VARCHAR(1000) NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  issued_at DATETIME NULL,
  voided_at DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_invoice_patient FOREIGN KEY (patient_id) REFERENCES patients(patient_id) ON DELETE RESTRICT,
  CONSTRAINT fk_invoice_allocation FOREIGN KEY (allocation_id) REFERENCES allocations(allocation_id) ON DELETE RESTRICT,
  CONSTRAINT fk_invoice_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT chk_invoice_amounts CHECK (subtotal >= 0 AND discount_total >= 0 AND tax_total >= 0 AND total_amount >= 0),
  CONSTRAINT chk_invoice_total CHECK (total_amount = subtotal - discount_total + tax_total),
  INDEX idx_invoice_patient_date (patient_id, invoice_date),
  INDEX idx_invoice_status (status, due_date)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS invoice_items (
  invoice_item_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  invoice_id BIGINT UNSIGNED NOT NULL,
  charge_id BIGINT UNSIGNED NULL,
  description VARCHAR(255) NOT NULL,
  quantity DECIMAL(10,2) NOT NULL DEFAULT 1.00,
  unit_price DECIMAL(12,2) NOT NULL,
  discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
  tax_rate DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  line_subtotal DECIMAL(14,2) NOT NULL,
  tax_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  line_total DECIMAL(14,2) NOT NULL,
  service_start DATETIME NULL,
  service_end DATETIME NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_item_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_item_charge FOREIGN KEY (charge_id) REFERENCES charge_catalog(charge_id) ON DELETE SET NULL,
  CONSTRAINT chk_item_values CHECK (quantity > 0 AND unit_price >= 0 AND discount_amount >= 0 AND tax_rate BETWEEN 0 AND 100 AND line_subtotal >= 0 AND tax_amount >= 0 AND line_total >= 0),
  CONSTRAINT chk_item_dates CHECK (service_end IS NULL OR service_start IS NULL OR service_end >= service_start),
  INDEX idx_items_invoice (invoice_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payments (
  payment_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  invoice_id BIGINT UNSIGNED NOT NULL,
  receipt_no VARCHAR(40) NOT NULL UNIQUE,
  amount DECIMAL(14,2) NOT NULL,
  payment_method ENUM('CASH','UPI','CARD','BANK_TRANSFER','INSURANCE','OTHER') NOT NULL,
  status ENUM('PENDING','SUCCESS','FAILED','REVERSED') NOT NULL DEFAULT 'PENDING',
  transaction_ref VARCHAR(180) NULL,
  received_by BIGINT UNSIGNED NULL,
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  notes VARCHAR(500) NULL,
  CONSTRAINT fk_payment_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_payment_receiver FOREIGN KEY (received_by) REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT chk_payment_amount CHECK (amount > 0),
  INDEX idx_payments_invoice_status (invoice_id, status, received_at),
  UNIQUE KEY uq_payment_transaction_ref (transaction_ref)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment_refunds (
  refund_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  payment_id BIGINT UNSIGNED NOT NULL,
  refund_no VARCHAR(40) NOT NULL UNIQUE,
  amount DECIMAL(14,2) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  status ENUM('REQUESTED','APPROVED','REJECTED','PROCESSED','FAILED') NOT NULL DEFAULT 'REQUESTED',
  requested_by BIGINT UNSIGNED NOT NULL,
  approved_by BIGINT UNSIGNED NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  approved_at DATETIME NULL,
  processed_at DATETIME NULL,
  external_ref VARCHAR(180) NULL,
  CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payments(payment_id) ON DELETE RESTRICT,
  CONSTRAINT fk_refund_requester FOREIGN KEY (requested_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT fk_refund_approver FOREIGN KEY (approved_by) REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT chk_refund_amount CHECK (amount > 0),
  INDEX idx_refunds_payment_status (payment_id, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS insurance_claims (
  claim_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  invoice_id BIGINT UNSIGNED NOT NULL,
  payer_type ENUM('PM_JAY','PRIVATE','CORPORATE','OTHER') NOT NULL,
  insurer_name VARCHAR(180) NOT NULL,
  policy_reference VARCHAR(120) NULL,
  claim_reference VARCHAR(120) NULL,
  claim_amount DECIMAL(14,2) NOT NULL,
  approved_amount DECIMAL(14,2) NULL,
  status ENUM('DRAFT','SUBMITTED','UNDER_REVIEW','APPROVED','PARTIALLY_APPROVED','REJECTED','SETTLED') NOT NULL DEFAULT 'DRAFT',
  submitted_at DATETIME NULL,
  decision_at DATETIME NULL,
  notes VARCHAR(1000) NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  CONSTRAINT fk_claim_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_claim_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT chk_claim_amount CHECK (claim_amount > 0 AND (approved_amount IS NULL OR approved_amount >= 0)),
  INDEX idx_claim_invoice_status (invoice_id, status)
) ENGINE=InnoDB;

-- ------------------------------- VIEWS --------------------------------------
-- Derived balances avoid storing a second, potentially inconsistent ledger total.
CREATE OR REPLACE VIEW v_invoice_balances AS
SELECT
  i.invoice_id,
  i.invoice_no,
  i.patient_id,
  i.allocation_id,
  i.status AS invoice_status,
  i.total_amount,
  COALESCE(p.successful_payments, 0.00) AS amount_paid,
  COALESCE(r.processed_refunds, 0.00) AS amount_refunded,
  (COALESCE(p.successful_payments, 0.00) - COALESCE(r.processed_refunds, 0.00)) AS net_amount_paid,
  GREATEST(i.total_amount - (COALESCE(p.successful_payments, 0.00) - COALESCE(r.processed_refunds, 0.00)), 0.00) AS balance_due
FROM invoices i
LEFT JOIN (
  SELECT invoice_id, SUM(amount) AS successful_payments
  FROM payments WHERE status = 'SUCCESS' GROUP BY invoice_id
) p ON p.invoice_id = i.invoice_id
LEFT JOIN (
  SELECT p2.invoice_id, SUM(pr.amount) AS processed_refunds
  FROM payment_refunds pr
  JOIN payments p2 ON p2.payment_id = pr.payment_id
  WHERE pr.status = 'PROCESSED'
  GROUP BY p2.invoice_id
) r ON r.invoice_id = i.invoice_id;

CREATE OR REPLACE VIEW v_ward_occupancy AS
SELECT
  w.ward_id, w.ward_name, w.ward_type, w.capacity,
  COUNT(b.bed_id) AS total_beds,
  SUM(CASE WHEN b.status = 'AVAILABLE' THEN 1 ELSE 0 END) AS available_beds,
  SUM(CASE WHEN b.status = 'OCCUPIED' THEN 1 ELSE 0 END) AS occupied_beds,
  SUM(CASE WHEN b.status IN ('RESERVED','CLEANING','MAINTENANCE','OUT_OF_SERVICE') THEN 1 ELSE 0 END) AS unavailable_beds
FROM wards w
LEFT JOIN beds b ON b.ward_id = w.ward_id
GROUP BY w.ward_id, w.ward_name, w.ward_type, w.capacity;

CREATE OR REPLACE VIEW v_patient_demographics AS
SELECT p.patient_id, p.uhid, p.full_name, p.date_of_birth,
       TIMESTAMPDIFF(YEAR, p.date_of_birth, CURRENT_DATE()) AS age_years,
       (TIMESTAMPDIFF(YEAR, p.date_of_birth, CURRENT_DATE()) >=
         CAST((SELECT setting_value FROM hospital_settings WHERE setting_key = 'senior_citizen_age_threshold') AS UNSIGNED)) AS is_senior_citizen,
       p.gender, p.phone, p.record_status
FROM patients p;

CREATE OR REPLACE VIEW v_active_allocations AS
SELECT a.allocation_id, a.patient_id, p.uhid, p.full_name AS patient_name,
       a.bed_id, b.bed_code, w.ward_id, w.ward_name, a.admit_time,
       a.expected_discharge, a.allocated_by
FROM allocations a
JOIN patients p ON p.patient_id = a.patient_id
JOIN beds b ON b.bed_id = a.bed_id
JOIN wards w ON w.ward_id = b.ward_id
WHERE a.status = 'ACTIVE';

-- --------------------------- INITIAL CONFIG DATA ----------------------------
INSERT INTO hospital_profile (hospital_id, hospital_code, hospital_name, timezone_name)
VALUES (1, 'IHBAS-DEMO', 'IHBAS Demonstration Hospital', 'Asia/Kolkata')
ON DUPLICATE KEY UPDATE hospital_name = VALUES(hospital_name), timezone_name = VALUES(timezone_name);

INSERT INTO hospital_settings (setting_key, setting_value, description) VALUES
('senior_citizen_age_threshold', '60', 'Age in completed years; 60 and above qualifies as senior citizen.'),
('otp_channel', 'SMS', 'OTP delivery channel selected by project owner.'),
('otp_length', '6', 'Numeric OTP length.'),
('otp_expiry_minutes', '5', 'OTP validity window.'),
('otp_max_attempts', '5', 'Maximum verification attempts per OTP.'),
('session_idle_timeout_minutes', '30', 'Proposed server-side idle session timeout.'),
('reservation_max_minutes', '120', 'Maximum reservation duration in minutes.'),
('billing_currency', 'INR', 'Default billing currency.'),
('billing_cycle', 'PER_24_HOURS', 'Proposed bed billing unit; hospital policy must confirm.'),
('billing_grace_minutes', '0', 'Configurable bed billing grace period; confirm with hospital policy.')
ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), description = VALUES(description);

INSERT INTO departments (name, description) VALUES
('General Medicine','General medical services'),
('Cardiology','Cardiac care'),
('Neurology','Neurological services'),
('Orthopedics','Bone and joint services'),
('Surgery','Surgical services'),
('Critical Care','Intensive and high-dependency care'),
('Pediatrics','Child healthcare'),
('Neonatology','Newborn care'),
('Obstetrics and Gynecology','Maternity services'),
('Emergency Medicine','Emergency assessment and care')
ON DUPLICATE KEY UPDATE description = VALUES(description);

INSERT INTO doctors (doctor_code, full_name, department_id, active)
SELECT d.doctor_code, d.full_name, dep.department_id, TRUE
FROM (
  SELECT 'DOC-001' doctor_code, 'Dr. Aarav Mehta' full_name, 'Cardiology' department_name UNION ALL
  SELECT 'DOC-002','Dr. Ananya Sharma','Neurology' UNION ALL
  SELECT 'DOC-003','Dr. Rohan Verma','Orthopedics' UNION ALL
  SELECT 'DOC-004','Dr. Priya Singh','Pediatrics' UNION ALL
  SELECT 'DOC-005','Dr. Kabir Das','General Medicine' UNION ALL
  SELECT 'DOC-006','Dr. Meera Gupta','Surgery' UNION ALL
  SELECT 'DOC-007','Dr. Ishita Patel','Obstetrics and Gynecology' UNION ALL
  SELECT 'DOC-008','Dr. Vihaan Kumar','Critical Care' UNION ALL
  SELECT 'DOC-009','Dr. Diya Mishra','Neonatology' UNION ALL
  SELECT 'DOC-010','Dr. Arjun Yadav','Emergency Medicine'
) d
JOIN departments dep ON dep.name = d.department_name
ON DUPLICATE KEY UPDATE full_name = VALUES(full_name), department_id = VALUES(department_id), active = TRUE;

INSERT INTO esi_levels (esi_level, label, description, emergency_flag) VALUES
(1,'Immediate','Requires immediate life-saving intervention.',TRUE),
(2,'Emergent','High-risk or time-critical condition.',TRUE),
(3,'Urgent','Requires multiple resources or urgent assessment.',FALSE),
(4,'Less Urgent','Requires one resource.',FALSE),
(5,'Non Urgent','Requires no immediate resources.',FALSE)
ON DUPLICATE KEY UPDATE label = VALUES(label), description = VALUES(description), emergency_flag = VALUES(emergency_flag);

-- SRS Section 6.1: exact 300-bed ward mix. Ward-wise counts sum to 300.
INSERT INTO wards (ward_name, ward_type, floor_label, gender_policy, capacity, status) VALUES
('General Ward','General','Ground','ANY',120,'ACTIVE'),
('Semi-Private Ward','Semi-Private','First','ANY',50,'ACTIVE'),
('Private Ward','Private','Second','ANY',40,'ACTIVE'),
('ICU','ICU','Third','ANY',30,'ACTIVE'),
('HDU','HDU','Third','ANY',10,'ACTIVE'),
('CCU','CCU','Third','ANY',10,'ACTIVE'),
('NICU','NICU','Fourth','ANY',10,'ACTIVE'),
('Pediatric Ward','Pediatric','First','ANY',15,'ACTIVE'),
('Maternity Ward','Maternity','Second','FEMALE_ONLY',10,'ACTIVE'),
('Isolation Ward','Isolation','Ground','ANY',5,'ACTIVE')
ON DUPLICATE KEY UPDATE ward_type = VALUES(ward_type), floor_label = VALUES(floor_label), capacity = VALUES(capacity), status = VALUES(status);

-- Generate precisely the SRS ward capacities. Bed codes are stable and rerunnable.
INSERT INTO beds (bed_code, ward_id, category, floor_label, daily_charge,
                  has_oxygen, has_ventilator, has_monitor, attached_bathroom,
                  isolation_capable, status)
SELECT CONCAT(x.prefix, '-', LPAD(seq.n, 3, '0')),
       w.ward_id, w.ward_type,
       CONCAT('Floor ', CEIL(seq.n / 30)),
       CASE w.ward_type
         WHEN 'General' THEN 1000 + MOD(seq.n * 37, 1001)
         WHEN 'Semi-Private' THEN 2500 + MOD(seq.n * 53, 1501)
         WHEN 'Private' THEN 5000 + MOD(seq.n * 97, 4001)
         WHEN 'ICU' THEN 12000 + MOD(seq.n * 131, 13001)
         WHEN 'HDU' THEN 8000 + MOD(seq.n * 67, 4001)
         WHEN 'CCU' THEN 12000 + MOD(seq.n * 89, 8001)
         WHEN 'NICU' THEN 10000 + MOD(seq.n * 71, 8001)
         WHEN 'Pediatric' THEN 2000 + MOD(seq.n * 43, 2001)
         WHEN 'Maternity' THEN 3000 + MOD(seq.n * 59, 3001)
         WHEN 'Isolation' THEN 4000 + MOD(seq.n * 41, 3001)
       END,
       w.ward_type IN ('ICU','HDU','CCU','NICU','Isolation'),
       w.ward_type = 'ICU',
       w.ward_type IN ('ICU','HDU','CCU','NICU'),
       w.ward_type IN ('Semi-Private','Private','Maternity'),
       w.ward_type = 'Isolation',
       'AVAILABLE'
FROM wards w
JOIN (
  SELECT 'General Ward' ward_name, 'GEN' prefix UNION ALL
  SELECT 'Semi-Private Ward','SEM' UNION ALL
  SELECT 'Private Ward','PRI' UNION ALL
  SELECT 'ICU','ICU' UNION ALL
  SELECT 'HDU','HDU' UNION ALL
  SELECT 'CCU','CCU' UNION ALL
  SELECT 'NICU','NIC' UNION ALL
  SELECT 'Pediatric Ward','PED' UNION ALL
  SELECT 'Maternity Ward','MAT' UNION ALL
  SELECT 'Isolation Ward','ISO'
) x ON x.ward_name = w.ward_name
JOIN (
  SELECT ones.d + (tens.d * 10) + (hundreds.d * 100) + 1 AS n
  FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 d UNION ALL SELECT 1) hundreds
) seq ON seq.n <= w.capacity
WHERE NOT EXISTS (
  SELECT 1 FROM beds existing
  WHERE existing.bed_code = CONCAT(x.prefix, '-', LPAD(seq.n, 3, '0'))
);

INSERT INTO charge_catalog (charge_code, description, category, unit_price, billing_unit, tax_rate, active) VALUES
('BED-GENERAL','General ward bed charge','BED',0.00,'PER_DAY',0.00,TRUE),
('BED-PRIVATE','Private ward bed charge','BED',0.00,'PER_DAY',0.00,TRUE),
('BED-ICU','ICU bed charge','BED',0.00,'PER_DAY',0.00,TRUE),
('CONSULT-GEN','General consultation','DOCTOR',0.00,'PER_VISIT',0.00,TRUE),
('NURSING-GEN','Nursing service','NURSING',0.00,'PER_DAY',0.00,TRUE),
('SERVICE-OTHER','Other hospital service','OTHER',0.00,'PER_ITEM',0.00,TRUE)
ON DUPLICATE KEY UPDATE description = VALUES(description);

-- SRS Section 11: 500 synthetic demo patients with varied ages and profiles.
-- Phone values intentionally use a reserved-looking all-zero prefix and are NOT
-- deliverable contact numbers. Replace only with consented test fixtures if needed.
INSERT INTO patients (uhid, full_name, date_of_birth, gender, blood_group, phone,
                      email, address, emergency_contact_name, emergency_contact_phone,
                      insurance_type, record_status)
SELECT CONCAT('IHBAS-DEMO-', LPAD(seq.n, 6, '0')),
       CONCAT(fn.nm, ' ', ln.nm),
       DATE_SUB(CURRENT_DATE(), INTERVAL MOD(seq.n * 173, 95 * 365) DAY),
       CASE MOD(seq.n, 3) WHEN 0 THEN 'FEMALE' WHEN 1 THEN 'MALE' ELSE 'OTHER' END,
       ELT(1 + MOD(seq.n, 8),'A_POS','A_NEG','B_POS','B_NEG','AB_POS','AB_NEG','O_POS','O_NEG'),
       CONCAT('000000', LPAD(seq.n, 4, '0')),
       CONCAT('ihbas.demo.', LPAD(seq.n, 4, '0'), '@example.invalid'),
       CONCAT('Synthetic Demo Address ', seq.n, ', India'),
       CONCAT('Emergency Contact ', seq.n),
       CONCAT('000000', LPAD(500 + seq.n, 4, '0')),
       CASE MOD(seq.n, 4) WHEN 0 THEN 'CASH' WHEN 1 THEN 'PM_JAY' WHEN 2 THEN 'PRIVATE' ELSE 'CORPORATE' END,
       'ACTIVE'
FROM (
  SELECT ones.d + (tens.d * 10) + (hundreds.d * 100) + 1 AS n
  FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
              UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) hundreds
) seq
JOIN (
  SELECT 1 id, 'Aarav' nm UNION ALL SELECT 2,'Vivaan' UNION ALL SELECT 3,'Aditya'
  UNION ALL SELECT 4,'Vihaan' UNION ALL SELECT 5,'Arjun' UNION ALL SELECT 6,'Sai'
  UNION ALL SELECT 7,'Reyansh' UNION ALL SELECT 8,'Ayaan' UNION ALL SELECT 9,'Krishna'
  UNION ALL SELECT 10,'Ishaan' UNION ALL SELECT 11,'Ananya' UNION ALL SELECT 12,'Diya'
  UNION ALL SELECT 13,'Aadhya' UNION ALL SELECT 14,'Saanvi' UNION ALL SELECT 15,'Pari'
  UNION ALL SELECT 16,'Priya' UNION ALL SELECT 17,'Kavya' UNION ALL SELECT 18,'Meera'
  UNION ALL SELECT 19,'Riya' UNION ALL SELECT 20,'Anika'
) fn ON fn.id = 1 + MOD(seq.n - 1, 20)
JOIN (
  SELECT 1 id, 'Sharma' nm UNION ALL SELECT 2,'Verma' UNION ALL SELECT 3,'Singh'
  UNION ALL SELECT 4,'Kumar' UNION ALL SELECT 5,'Gupta' UNION ALL SELECT 6,'Yadav'
  UNION ALL SELECT 7,'Patel' UNION ALL SELECT 8,'Mishra' UNION ALL SELECT 9,'Das'
  UNION ALL SELECT 10,'Choudhary'
) ln ON ln.id = 1 + MOD(FLOOR((seq.n - 1) / 20), 10)
WHERE seq.n <= 500
  AND NOT EXISTS (
  SELECT 1 FROM patients p WHERE p.uhid = CONCAT('IHBAS-DEMO-', LPAD(seq.n, 6, '0'))
);

-- -------------------------- DATA INTEGRITY NOTES ----------------------------
-- 1) Create staff users through the application/admin bootstrap process using
--    BCrypt hashes. Do NOT insert plaintext passwords or publish default creds.
-- 2) The script seeds the exact 300-bed mix and 500 synthetic patients required
--    by SRS Section 6.1 and the testing acceptance criteria.
-- 3) Senior-citizen age is calculated from patients.date_of_birth at query time;
--    do not store an independently editable age column.
-- 4) Full billing is an explicitly approved project extension. Insurance claim
--    tracking is local recordkeeping only; no insurer/payment gateway is wired.
-- 5) Patient self-registration is part of the SRS. The application must verify
--    SMS OTP before creating/linking a patient user account and issuing a UHID.
--    The 500 seed patient rows are synthetic profile fixtures and intentionally
--    have no login account linked; create test accounts through the app bootstrap.
-- 6) `allocations` active generated columns enforce one active allocation per
--    patient and per bed. `allocation_bed_history` preserves each bed interval.
-- 7) A reservation must be created/expired/confirmed by a transactional service.
--    Expired reservations require a scheduled cleanup job; CHECK constraints
--    cannot compare expires_at with the current clock at every later moment.
-- 8) `invoice_items` and invoice totals must be inserted/updated in a single
--    service transaction; successful payments and processed refunds are the
--    ledger source for v_invoice_balances.
-- 9) Bed status, allocation, transfer, discharge, housekeeping, audit and
--    notification-outbox writes must be coordinated in Java transactions.
-- 10) This script avoids MySQL-only collations and recursive CTE syntax so it
--     can be imported by the MariaDB 10.4.x version bundled with XAMPP.
-- 11) Real deployment requires reviewed retention policy, encryption key
--     management, HTTPS, private document storage, backups and restore tests.

-- Quick verification after import:
-- SELECT COUNT(*) AS table_count FROM information_schema.tables
--  WHERE table_schema = 'ihbas_db' AND table_type = 'BASE TABLE';
-- SELECT * FROM v_ward_occupancy;
-- SELECT * FROM v_patient_demographics;
-- SELECT COUNT(*) AS seeded_beds FROM beds;       -- expected: 300 on clean DB
-- SELECT COUNT(*) AS seeded_patients FROM patients; -- expected: 500 on clean DB
-- SELECT * FROM v_invoice_balances;
