-- Create the database if it doesn't exist
CREATE DATABASE IF NOT EXISTS orderdb;

-- Use the orderdb database
USE orderdb;

-- Create the orders table if it doesn't exist
CREATE TABLE IF NOT EXISTS orders (
    id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    customer_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    status ENUM('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED') NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Add indexes for frequently queried columns if needed
-- For this assignment, primary key 'id' is sufficient for direct lookups.
-- If you were to query by customer_id or product_id frequently, you'd add:
-- CREATE INDEX idx_customer_id ON orders (customer_id);
-- CREATE INDEX idx_product_id ON orders (product_id);

-- Optionally insert sample records (PENDING orders)
INSERT IGNORE INTO orders (id, product_id, customer_id, quantity, status) VALUES
('order123', 'prodA', 'custX', 2, 'PENDING'),
('order456', 'prodB', 'custY', 1, 'PENDING'),
('order789', 'prodC', 'custZ', 5, 'PENDING');

-- Verify table creation and data
SELECT 'Table "orders" created and sample data inserted.' AS Message;
SELECT * FROM orders;
