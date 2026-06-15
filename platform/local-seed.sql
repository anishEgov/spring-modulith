-- Minimal seed data to exercise the two converted in-process edges locally.
-- Apply after the app's Flyway migrations have created the schema:
--   docker exec -i platform-postgres psql -U postgres -d platform -f - < local-seed.sql

-- idgen edge: format definition the individual module asks for (idName 'individual.id').
-- [SEQ_EG_INDIVIDUAL] is auto-created by idgen (autocreate.request.seq=true) -> IND-000001, ...
INSERT INTO id_generator (idname, tenantid, format, sequencenumber)
VALUES ('individual.id', 'dev', 'IND-[SEQ_EG_INDIVIDUAL]', 0)
ON CONFLICT (idname, tenantid) DO NOTHING;

-- localization edge: the SMS template the individual create-notification looks up.
INSERT INTO message (id, locale, code, message, tenantid, module, createdby)
VALUES ('msg-ind-create', 'en_IN', 'INDIVIDUAL_NOTIFICATION_ON_CREATE',
        'Dear {individualName}, your registration ID is {registrationID}.',
        'dev', 'rainmaker-masters', 1)
ON CONFLICT DO NOTHING;
