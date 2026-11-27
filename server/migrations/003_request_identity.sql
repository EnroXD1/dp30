ALTER TABLE requests ADD COLUMN request_uid uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE requests ADD CONSTRAINT requests_request_uid_key UNIQUE (request_uid);

-- Older receipts retain their original business snapshot and gain its stable identifiers.
UPDATE command_receipts AS c
SET response = c.response || jsonb_build_object('request_uid', r.request_uid, 'number', r.id)
FROM requests AS r WHERE c.request_id = r.id;
