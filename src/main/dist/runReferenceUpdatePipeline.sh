# script env setup
#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/ReferenceUpdatePipeline
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
CMDLINE_OPTIONS="-importMissingReferences -fixDuplicateReferences"

#
# script body
#
cd $APP_HOME
./_run.sh $CMDLINE_OPTIONS > $APP_HOME/annot_status.log 2>&1

mailx -s "[$SERVER] Pipeline to update RGD Reference data ran" RGD.devops@mcw.edu < $APP_HOME/annot_status.log
