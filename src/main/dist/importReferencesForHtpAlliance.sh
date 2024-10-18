#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/ReferenceUpdatePipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`

#
#
cd $APP_HOME
./_run.sh --importReferencesForAllianceHtp > $APP_HOME/refsForAllianceHtp.log 2>&1

mailx -s "[$SERVER] Import references for HTP Alliance OK" mtutaj@mcw.edu < $APP_HOME/logs/refsForAllianceHtp.log
