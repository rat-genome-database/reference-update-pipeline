-- Table maintained by the --retractedReferences module from the retraction data published by
-- Crossref (the Retraction Watch database). Every downloaded row that carries a PubMed id for the
-- retracted paper is kept, whether or not RGD has that reference.
--
-- The table is synchronized incrementally: rows new to the download are inserted, rows whose
-- content changed are updated, and rows that disappeared from the download are deleted.
--
-- RECORD_ID is Retraction Watch's own identifier for the row and is what the synchronization keys
-- on. It is needed because none of the other columns, alone or combined, identify a row uniquely --
-- the same paper legitimately appears more than once (a retraction as well as an earlier
-- expression of concern, for instance), and the download also contains fully duplicated rows.
--
-- ORIGINAL_PMID_RGD_ID, DATE_CREATED_IN_RGD and DATE_RETRACTED_IN_RGD are filled in only when RGD
-- holds a reference for ORIGINAL_PMID. DATE_RETRACTED_IN_RGD is the point at which the reference
-- was withdrawn in RGD, taken from RGD_IDS.LAST_MODIFIED_DATE -- there is no dedicated
-- 'date withdrawn' column, so it is the closest approximation available, and it is null for
-- references that are still active.
--
-- NATURE distinguishes a real retraction from a correction, an expression of concern or a
-- reinstatement; only 'Retraction' means the paper was actually retracted.

CREATE TABLE references_retracted (
    record_id              NUMBER NOT NULL,
    original_pmid          VARCHAR2(20) NOT NULL,
    retraction_pmid        VARCHAR2(20),
    retraction_date        DATE,
    nature                 VARCHAR2(100),
    reason                 VARCHAR2(2000),
    original_pmid_rgd_id   NUMBER,
    date_created_in_rgd    DATE,
    date_retracted_in_rgd  DATE
);

CREATE UNIQUE INDEX references_retracted_uq ON references_retracted (record_id);
CREATE INDEX references_retracted_pmid_idx ON references_retracted (original_pmid);
CREATE INDEX references_retracted_rgdid_idx ON references_retracted (original_pmid_rgd_id);
