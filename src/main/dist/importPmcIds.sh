#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/ReferenceUpdatePipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

#
#
cd $APP_HOME
./_run.sh --importPmcIds > $APP_HOME/import_pmc_ids.log 2>&1

mailx -s "[$SERVER] Import PMC ids OK" mtutaj@mcw.edu < $APP_HOME/logs/pmc_ids_summary.log
