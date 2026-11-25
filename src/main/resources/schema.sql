DROP ALL OBJECTS;
CREATE TABLE nonprofit (id VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL);
CREATE TABLE staff (
 id VARCHAR(64) PRIMARY KEY, nonprofit_id VARCHAR(64) NOT NULL REFERENCES nonprofit(id),
 role VARCHAR(32) NOT NULL CHECK(role IN ('CASE_WORKER','PROGRAM_MANAGER','FINANCE')),
 UNIQUE(nonprofit_id,id)
);
CREATE TABLE participant (
 id VARCHAR(64) PRIMARY KEY, nonprofit_id VARCHAR(64) NOT NULL REFERENCES nonprofit(id),
 display_name VARCHAR(100) NOT NULL, UNIQUE(nonprofit_id,id)
);
CREATE TABLE fund (
 id VARCHAR(64) PRIMARY KEY, nonprofit_id VARCHAR(64) NOT NULL REFERENCES nonprofit(id),
 name VARCHAR(100) NOT NULL, allocation_cents BIGINT NOT NULL CHECK(allocation_cents BETWEEN 0 AND 9007199254740991),
 currency CHAR(3) NOT NULL DEFAULT 'USD' CHECK(currency='USD'), UNIQUE(nonprofit_id,id)
);
CREATE TABLE assistance_request (
 id VARCHAR(64) PRIMARY KEY, nonprofit_id VARCHAR(64) NOT NULL REFERENCES nonprofit(id),
 participant_id VARCHAR(64) NOT NULL, fund_id VARCHAR(64) NOT NULL, creator_id VARCHAR(64) NOT NULL,
 client_submission_id VARCHAR(64) NOT NULL, purpose VARCHAR(1000) NOT NULL CHECK(CHAR_LENGTH(TRIM(purpose))>0),
 amount_cents BIGINT NOT NULL CHECK(amount_cents BETWEEN 1 AND 9007199254740991),
 status VARCHAR(16) NOT NULL CHECK(status IN ('Pending','Approved','Rejected','Paid')),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 decision_by VARCHAR(64), decision_at TIMESTAMP WITH TIME ZONE,
 paid_by VARCHAR(64), paid_at TIMESTAMP WITH TIME ZONE,
 FOREIGN KEY(nonprofit_id,participant_id) REFERENCES participant(nonprofit_id,id),
 FOREIGN KEY(nonprofit_id,fund_id) REFERENCES fund(nonprofit_id,id),
 FOREIGN KEY(nonprofit_id,creator_id) REFERENCES staff(nonprofit_id,id),
 FOREIGN KEY(nonprofit_id,decision_by) REFERENCES staff(nonprofit_id,id),
 FOREIGN KEY(nonprofit_id,paid_by) REFERENCES staff(nonprofit_id,id),
 UNIQUE(nonprofit_id,creator_id,client_submission_id),
 CHECK ((status='Pending' AND decision_by IS NULL AND decision_at IS NULL AND paid_by IS NULL AND paid_at IS NULL)
 OR (status IN ('Approved','Rejected') AND decision_by IS NOT NULL AND decision_at IS NOT NULL AND paid_by IS NULL AND paid_at IS NULL)
 OR (status='Paid' AND decision_by IS NOT NULL AND decision_at IS NOT NULL AND paid_by IS NOT NULL AND paid_at IS NOT NULL))
);
CREATE INDEX request_fund_status ON assistance_request(nonprofit_id,fund_id,status);
INSERT INTO nonprofit VALUES ('cb','Community Bridge'),('hs','Harbor Support');
INSERT INTO staff VALUES ('cb-case-worker','cb','CASE_WORKER'),('cb-program-manager','cb','PROGRAM_MANAGER'),('cb-finance','cb','FINANCE'),('hs-case-worker','hs','CASE_WORKER'),('hs-program-manager','hs','PROGRAM_MANAGER'),('hs-finance','hs','FINANCE');
INSERT INTO participant VALUES ('cb-p1','cb','Alex River'),('cb-p2','cb','Taylor Field'),('hs-p1','hs','Jordan Lake'),('hs-p2','hs','Morgan Hill');
INSERT INTO fund(id,nonprofit_id,name,allocation_cents) VALUES ('cb-emergency','cb','Emergency Fund',1000000),('cb-transport','cb','Transport Fund',200000),('hs-emergency','hs','Emergency Fund',1000000),('hs-transport','hs','Transport Fund',200000);
