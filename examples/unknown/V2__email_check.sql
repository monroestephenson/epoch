ALTER TABLE customers ADD CONSTRAINT email_not_empty CHECK (length(email) > 0);
