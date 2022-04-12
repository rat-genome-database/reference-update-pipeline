Apr 12, 2022
  fixed log4j2.xml

Feb 04, 2022
  updated log4j to avoid zero-day exploit

May 10, 2020
  updated jar dependencies

ver 1.4.5 (Sep 7, 2018)
  updated build files to GRADLE from ANT

ver 1.4.4 (Dec 30, 2016)
  added cmdline option to fixDuplicateAuthors

ver 1.4.3 (Dec 1, 2016)
  config: updated green.rgd.mcw.edu --> ontomate.rgd.mcw.edu

ver 1.4.2 (Sep 12, 2016)
  config: updated HTTP to HTTPS for NCBI links

ver 1.4.1 (May 16, 2016)
  config: updated url to text-mining service from MORGAN to GREEN

ver 1.4.0 (Apr 12, 2016)
  added module2: import into RGD missing references: for those PubMed ids that were created in RGD
    within the last few days  -- per RGDD-1190

ver 1.3.1 (Apr 9, 2015)
  updated jars, commented out unused dao code

ver 1.3 (Dec 6, 2013)
  enhanced robustness: if downloaded xml file is partial, the download process is repeated

ver 1.2 (Nov 19, 2013)
  fixed bug in ReferenceUpdateObject that could (and was) a NullPointerException
  fixed typo in ReferenceUpdateObject that was wrongly setting Issue
  downloaded temporary xml files are deleted after processing
  reduced amount of logging (of little informational value) in the summary email