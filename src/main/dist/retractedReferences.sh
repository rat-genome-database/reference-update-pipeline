#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/reference-update-pipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

#
#
cd $APP_HOME
./_run.sh --retractedReferences > $APP_HOME/retracted_references.log 2>&1

# the summary, followed by the two curator reports
REPORT=$APP_HOME/logs/retracted_references_report.log
cat $APP_HOME/logs/retracted_references_summary.log > $REPORT
echo "" >> $REPORT
cat $APP_HOME/logs/retracted_refs_active.log >> $REPORT
echo "" >> $REPORT
cat $APP_HOME/logs/retracted_refs_withdrawn.log >> $REPORT

mailx -s "[$SERVER] Retracted references OK" mtutaj@mcw.edu < $REPORT
