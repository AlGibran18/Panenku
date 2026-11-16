-- Existing accounts can set a display name through their own profile.
ALTER TABLE users ADD COLUMN display_name VARCHAR(255);
