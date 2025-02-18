# script env setup
#
. /etc/profile

APP_HOME=/home/rgddata/pipelines/reference-update-pipeline/
SERVER=`hostname -s | tr '[a-z]' '[A-Z]'`
CMDLINE_OPTIONS="-fixDuplicateAuthors"

cd $APP_HOME
./_run.sh $CMDLINE_OPTIONS | tee  fix_authors.log

mailx -s "[$SERVER] Pipeline to fix authors OK" mtutaj@mcw.edu<fix_authors.log
