#!/usr/bin/env bash
. /etc/profile

APPNAME=ReferenceUpdatePipeline
APPDIR=/home/rgddata/pipelines/$APPNAME

cd $APPDIR
DB_OPTS="-Dspring.config=$APPDIR/../properties/default_db.xml"
LOG4J_OPTS="-Dlog4j.configurationFile=file://$APPDIR/properties/log4j2.xml"
export REFERENCE_UPDATE_PIPELINE_OPTS="$DB_OPTS $LOG4J_OPTS"

bin/$APPNAME "$@"
