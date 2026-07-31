-- Archive of annotations removed from FULL_ANNOT by the --retractedReferences module because the
-- paper they were based on was retracted. It carries the same columns as FULL_ANNOT, so that an
-- annotation can be reconstructed in full if a curator decides it should be reinstated from a
-- non-retracted source. LAST_MODIFIED_DATE is overwritten with the time of the move; every other
-- column keeps the value it had in FULL_ANNOT.
--
-- Created as a copy of FULL_ANNOT's shape, which keeps the two in step and means the archiving
-- INSERT ... SELECT * needs no column list:
--
--     CREATE TABLE full_annot_retracted AS SELECT * FROM full_annot WHERE 1=0;
--
-- If FULL_ANNOT ever gains a column, the archiving insert fails loudly rather than dropping data;
-- add the column here to fix it.
--
-- Note: deleting a FULL_ANNOT row cascades to FULL_ANNOT_INDEX (FK_FULL_ANNOT_INDEX_FAK is
-- ON DELETE CASCADE). Those rows are a derived ontology-closure index used for searching and are
-- rebuilt from the annotation, so they are not archived.

CREATE TABLE full_annot_retracted AS SELECT * FROM full_annot WHERE 1=0;

CREATE UNIQUE INDEX full_annot_retracted_uq ON full_annot_retracted (full_annot_key);
CREATE INDEX full_annot_retracted_ref_idx ON full_annot_retracted (ref_rgd_id);
