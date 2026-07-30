#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/reference-update-pipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

#
#
cd $APP_HOME
./_run.sh --retractedReferences > $APP_HOME/retracted_references.log 2>&1

mailx -s "[$SERVER] Retracted references OK" mtutaj@mcw.edu < $APP_HOME/logs/retracted_references_summary.log
