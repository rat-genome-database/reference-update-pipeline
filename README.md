# reference-update-pipeline
Reference Update Pipeline:

1) fixes duplicate RGD IDs for each reference
2) imports missing references for PubMed ids created within last few days
3) imports PMC ids for active references in RGD
4) handles references whose paper has been retracted (see below)

---

# Retracted references

Run with `--retractedReferences`, or through `retractedReferences.sh`.

RGD had no way of noticing that a paper it had curated was later retracted. This module finds
those papers, records them, reports what they affect, and — once taken out of dry run — withdraws
the reference and moves its annotations out of `FULL_ANNOT`.

## Where the data comes from, and why not PubMed

The obvious source is PubMed, but it does not work:

* NLM publishes **no standalone file of retracted publications**. The FTP site carries only the
  annual baseline, the daily updates and journal lists.
* Retractions exist in PubMed only as publication types inside the full baseline XML, or through an
  E-utilities query — and E-utilities **refuses to return more than 9,999 records**, against roughly
  33,500 citations tagged `Retracted Publication`. NCBI's own error message points at EDirect.
* PubMed records **no reason** for a retraction in any structured form.

One further trap: NLM's documentation says retraction notices carry the publication type
`"Retraction of Publication"`. That value returns **zero** results. The indexed name is
`"Retraction Notice"` (D016440).

So the source is the **Retraction Watch database**, which Crossref publishes as a single free CSV.
It is one download, it carries the PubMed id of both the retracted paper and its retraction notice,
and it states the nature and the reason of every retraction.

## Nature: not everything in the file is a retraction

The file also carries corrections, expressions of concern and reinstatements. Only rows whose
`RetractionNature` matches the `retractionNature` property (`Retraction`) are treated as retracted.

This distinction is load-bearing, not theoretical. Of the rows that match an RGD reference, roughly
388 are retractions but a further 81 are corrections or expressions of concern. Ignoring nature
would withdraw 81 references whose papers are perfectly valid, and delete their annotations.

Reinstatements matter too: a paper can be retracted and later reinstated, and must not be withdrawn.

## REFERENCES_RETRACTED

DDL in `references_retracted.sql`. Holds every downloaded row that carries a PubMed id for the
retracted paper — whether or not RGD has the reference — with the RGD reference id and dates filled
in for the ones RGD does hold.

The table is **synchronized incrementally**: new rows are inserted, changed rows updated, rows that
dropped out of the download deleted, and everything else left alone.

Synchronizing needs a stable key, and the obvious candidates do not provide one. The same paper
legitimately appears more than once (a retraction alongside an earlier expression of concern), and
the download also contains a few hundred rows that are duplicates across every stored column. So the
key is `RECORD_ID`, Retraction Watch's own row identifier, which carries a unique index.

`DATE_RETRACTED_IN_RGD` is derived from `RGD_IDS.LAST_MODIFIED_DATE` for withdrawn references.
**It is an approximation, not a withdrawal timestamp** — RGD stores no record of when an object's
status changed, and for a substantial share of withdrawn references `LAST_MODIFIED_DATE` is
demonstrably not the moment of withdrawal. Treat it as a hint.

## What the module does to a retracted reference

Guarded by the `dryRun` bean property, which is **`true` by default**. In dry run the module reports
everything it would do and changes nothing. Set it to `false` to let it act.

For each reference whose paper was retracted:

1. **Its annotations are moved** out of `FULL_ANNOT` into `FULL_ANNOT_RETRACTED` (DDL in
   `full_annot_retracted.sql`), with `LAST_MODIFIED_DATE` stamped at the time of the move and every
   other column preserved. This happens **whatever the reference's status** — a reference withdrawn
   by hand can still be carrying annotations.
2. **The reference is withdrawn** (`RGD_IDS.OBJECT_STATUS`), if it is still active.
3. **`RETRACTED: ` is prepended to its title**, unless it is there already.

Re-running is safe: annotations already moved are gone from `FULL_ANNOT`, an already-withdrawn
reference is left alone, and a title is never prefixed twice.

### Two things to know about the annotation move

`AnnotationDAO.getAnnotationsByReference()` is **deliberately not used**. It joins `RGD_IDS` on the
annotated object and keeps only ACTIVE ones — its javadoc says *"annotations to non-active rgd
objects are skipped!"*. Using it left 5 of 570 affected annotations behind in `FULL_ANNOT`, still
pointing at a retracted paper. The module selects the annotations directly; rgdcore's
`deleteAnnotations()` does the delete.

The archive copy is `INSERT ... SELECT *`, not a field-by-field copy from `Annotation` objects,
because the data model has no counterpart for `CURATION_FLAG`.

Deleting a `FULL_ANNOT` row **cascades** to `FULL_ANNOT_INDEX` (`FK_FULL_ANNOT_INDEX_FAK` is
`ON DELETE CASCADE`) — around 19 index rows per annotation. Those are a derived ontology-closure
index used for searching and are rebuilt from the annotation, so they are not archived.

## PhenoMiner and gene expression: reported only

Studies built on a retracted paper are **reported and left untouched**; their experiment records
keep their curation status. Setting those to withdrawn is still to be implemented.

Studies are found through both link paths — `STUDY.REF_RGD_ID` and the `STUDY_REFERENCES` table.
Both are needed: `STUDY_REFERENCES` carries more distinct references than the direct column, so
checking only the direct column would miss most study links.

## Logs

| file | contents |
|---|---|
| `retracted_references_summary.log` | counts for the run |
| `retracted_refs_active.log` | retracted upstream, still active in RGD, with annotation breakdown |
| `retracted_refs_withdrawn.log` | already withdrawn in RGD but still carrying annotations |
| `retracted_annotations.log` | every annotation moved out of `FULL_ANNOT` — appended to, not overwritten |
| `retracted_studies.log` | PhenoMiner / expression studies needing curator review |

The two reference reports are ordered by the number of annotations at stake, highest first, and by
retraction date among references with the same count. Annotations are broken down by aspect together
with the ontology it belongs to, and by data source:

```
RGD:151893462  PMID:33230470  retracted:2022-05-20  notice PMID:35664706
      title:  Downregulation of MicroRNA-222 Reduces Insulin Resistance in Rats with PCOS...
      reason: Concerns/Issues about Data; Duplication of/in Image; Paper Mill
      annotations:16
          ontologies: aspect D (RDO)=9, aspect P (BP)=6, aspect N (MP)=1
          sources:    RGD=16
```

`retracted_annotations.log` is the record curators work from when deciding whether an annotation
could be re-made from a non-retracted paper:

```
RGD:155663482  PMID:33628824  annot_key:209349885  DOID:3910 [lung adenocarcinoma]
  aspect:D  object:DLL4 (RGD:1320241)  evidence:ISO  src:RGD
```

## Configuration

All on the `retractedReferences` bean in `properties/AppConfigure.xml`:

| property | purpose |
|---|---|
| `dryRun` | when true, report only and change nothing (default) |
| `retractionWatchUrl` | Crossref download url, including the contact address it asks callers for |
| `localFile` | where the CSV is downloaded to |
| `columns` | logical name to CSV column header, so a rename upstream is a config fix |
| `retractionNature` | the `RetractionNature` value that means the paper really was retracted |
| `retractionDateFormats` | date patterns tried, in order, against the retraction date |
| `titlePrefix` | prepended to the title of a withdrawn reference |
| `topReasonCount` | how many of the most frequent reasons to write to the debug log |
| `maxRetryCount`, `downloadRetryInterval` | download retry behaviour |

The aspect-to-ontology mapping used in the reports is **not** configured — it is read from the
`ONTOLOGIES` table through `OntologyXDAO`, so it cannot drift out of step with the database.
