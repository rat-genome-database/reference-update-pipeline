#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/reference-update-pipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

#
#
cd $APP_HOME
./_run.sh --importReferencesForAllianceHPO > $APP_HOME/refsForAllianceHPO.log 2>&1

mailx -s "[$SERVER] Import references for HPO Alliance OK" mtutaj@mcw.edu < $APP_HOME/logs/refsForAllianceHPO.log
