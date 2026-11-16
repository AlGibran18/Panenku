-- Distinguish the account holder from the store name shown in the catalog.
ALTER TABLE farmer_profiles ADD COLUMN owner_name VARCHAR(255);
