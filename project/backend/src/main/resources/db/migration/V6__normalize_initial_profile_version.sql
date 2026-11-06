-- Align existing newly registered profiles with the business and Agent version contract.
UPDATE user_profile SET profile_version = 1 WHERE profile_version = 0;
