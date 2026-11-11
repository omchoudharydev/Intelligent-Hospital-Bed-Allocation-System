-- IHBAS payment gateways: run once against the existing ihbas_db database.
-- This migration does not drop, truncate, or recreate hospital/patient data.

ALTER TABLE payments
  MODIFY payment_method ENUM('CASH','UPI','CARD','BANK_TRANSFER','INSURANCE','OTHER','RAZORPAY') NOT NULL;

CREATE TABLE IF NOT EXISTS payment_gateway_orders (
  gateway_order_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  invoice_id BIGINT UNSIGNED NOT NULL,
  created_by BIGINT UNSIGNED NOT NULL,
  local_receipt VARCHAR(40) NOT NULL UNIQUE,
  razorpay_order_id VARCHAR(80) NOT NULL UNIQUE,
  amount DECIMAL(14,2) NOT NULL,
  amount_paise BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'INR',
  status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
  razorpay_payment_id VARCHAR(80) NULL UNIQUE,
  failure_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_gateway_order_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_gateway_order_user FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT chk_gateway_order_amount CHECK (amount > 0 AND amount_paise > 0),
  INDEX idx_gateway_order_invoice (invoice_id, created_at),
  INDEX idx_gateway_order_status (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment_gateway_events (
  payment_gateway_event_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  gateway_event_id VARCHAR(100) NOT NULL UNIQUE,
  event_type VARCHAR(100) NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  event_status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at DATETIME NULL,
  INDEX idx_gateway_event_status (event_status, received_at)
) ENGINE=InnoDB;
