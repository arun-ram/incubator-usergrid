/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.usergrid.management.importer;

import com.amazonaws.SDKGlobalConfiguration;
import org.apache.usergrid.batch.JobExecution;
import org.apache.usergrid.batch.service.SchedulerService;
import org.apache.usergrid.corepersistence.CpSetup;
import org.apache.usergrid.corepersistence.util.CpNamingUtils;
import org.apache.usergrid.management.ManagementService;
import org.apache.usergrid.persistence.*;
import org.apache.usergrid.persistence.entities.FileImport;
import org.apache.usergrid.persistence.entities.Import;
import org.apache.usergrid.persistence.entities.JobData;
import org.apache.usergrid.persistence.index.query.Query.Level;
import org.apache.usergrid.persistence.queue.QueueManager;
import org.apache.usergrid.persistence.queue.QueueManagerFactory;
import org.apache.usergrid.persistence.queue.QueueScope;
import org.apache.usergrid.persistence.queue.QueueScopeFactory;
import org.apache.usergrid.services.ServiceManagerFactory;
import org.apache.usergrid.services.queues.ImportQueueListener;
import org.apache.usergrid.services.queues.ImportQueueMessage;
import org.apache.usergrid.utils.InflectionUtils;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;

import javax.annotation.PostConstruct;
import java.io.File;
import java.util.*;


public class ImportServiceImpl implements ImportService {

    public static final String IMPORT_ID = "importId";
    public static final String IMPORT_JOB_NAME = "importJob";
    public static final String FILE_IMPORT_ID = "fileImportId";
    public static final String FILE_IMPORT_JOB_NAME = "fileImportJob";
    public static final int HEARTBEAT_COUNT = 50;


    private static final Logger logger = LoggerFactory.getLogger(ImportServiceImpl.class);

    //injected the Entity Manager Factory
    protected EntityManagerFactory emf;

    //dependency injection
    private SchedulerService sch;

    private ServiceManagerFactory smf;

    //Dependency injection through spring
    private QueueManager qm;

    private QueueManagerFactory queueManagerFactory;

    //inject Management Service to access Organization Data
    private ManagementService managementService;
    private JsonFactory jsonFactory = new JsonFactory();


    @PostConstruct
    public void init(){

        //TODO: move this to a before or initialization method.

        //TODO: made queueName clearly defined.
        //smf = getApplicationContext().getBean(ServiceManagerFactory.class);

        String name = ImportQueueListener.QUEUE_NAME;
        QueueScopeFactory queueScopeFactory = CpSetup.getInjector().getInstance(QueueScopeFactory.class);
        QueueScope queueScope = queueScopeFactory.getScope(CpNamingUtils.MANAGEMENT_APPLICATION_ID, name);
        queueManagerFactory = CpSetup.getInjector().getInstance(QueueManagerFactory.class);
        qm = queueManagerFactory.getQueueManager(queueScope);
    }

    /**
     * This schedules the main import Job.
     *
     * @param config configuration of the job to be scheduled
     * @return it returns the UUID of the scheduled job
     */
    @Override
    public UUID schedule(Map<String, Object> config) throws Exception {

        if (config == null) {
            logger.error("import information cannot be null");
            return null;
        }

        EntityManager rootEm = null;
        try {
            rootEm = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
        } catch (Exception e) {
            logger.error("application doesn't exist within the current context");
            return null;
        }

        Import importUG = new Import();

        // create the import entity to store all metadata about the import job
        try {
            importUG = rootEm.create(importUG);
        } catch (Exception e) {
            logger.error("Import entity creation failed");
            return null;
        }

        // update state for import job to created
        importUG.setState(Import.State.CREATED);
        rootEm.update(importUG);

        // set data to be transferred to importInfo
        JobData jobData = new JobData();
        jobData.setProperty("importInfo", config);
        jobData.setProperty(IMPORT_ID, importUG.getUuid());

        long soonestPossible = System.currentTimeMillis() + 250; //sch grace period

        // schedule import job
        sch.createJob(IMPORT_JOB_NAME, soonestPossible, jobData);

        // update state for import job to created
        importUG.setState(Import.State.SCHEDULED);
        rootEm.update(importUG);

        return importUG.getUuid();
    }


    /**
     * This schedules the sub  FileImport Job
     *
     * @param file file to be scheduled
     * @return it returns the UUID of the scheduled job
     * @throws Exception
     */
    public JobData createFileTask( Map<String, Object> config, String file, EntityRef importRef ) throws Exception {

        logger.debug("scheduleFile() for import {}:{} file {}",
            new Object[]{importRef.getType(), importRef.getType(), file});

        EntityManager rootEm = null;

        try {
            rootEm = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
        } catch (Exception e) {
            logger.error("application doesn't exist within the current context");
            return null;
        }

        // create a FileImport entity to store metadata about the fileImport job
        String collectionName = config.get("collectionName").toString();
        UUID applicationId = (UUID)config.get("applicationId");
        FileImport fileImport = new FileImport( file, applicationId, collectionName );
        fileImport = rootEm.create(fileImport);

        Import importUG = rootEm.get(importRef, Import.class);

        try {
            // create a connection between the main import job and the sub FileImport Job
            rootEm.createConnection(importUG, "includes", fileImport);
        } catch (Exception e) {
            logger.error(e.getMessage());
            return null;
        }

        // mark the File Import Job as created
        fileImport.setState( FileImport.State.CREATED );
        rootEm.update( fileImport );

        // set data to be transferred to the FileImport Job
        JobData jobData = new JobData();
        jobData.setProperty( "File", file );
        jobData.setProperty(FILE_IMPORT_ID, fileImport.getUuid());
        jobData.addProperties(config);

        // update state of the job to Scheduled
        fileImport.setState(FileImport.State.SCHEDULED);
        rootEm.update(fileImport);

        rootEm.refreshIndex();

        return jobData;
    }


    /**
     * Schedule the file tasks.  This must happen in 2 phases.  The first is linking the sub files to the master the
     * second is scheduling them to run.
     */
    public JobData scheduleFileTasks( final JobData jobData ) {

        long soonestPossible = System.currentTimeMillis() + 250; //sch grace period

        // schedule file import job
        return sch.createJob(FILE_IMPORT_JOB_NAME, soonestPossible, jobData);
    }

    /**
     * Query Entity Manager for the state of the Import Entity. This corresponds to the GET /import
     */
    @Override
    public String getState(UUID uuid) throws Exception {
        if (uuid == null) {
            logger.error("getState(): UUID passed in cannot be null.");
            return "UUID passed in cannot be null";
        }

        EntityManager rootEm = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);

        //retrieve the import entity.
        Import importUG = rootEm.get(uuid, Import.class);

        if (importUG == null) {
            logger.error("getState(): no entity with that uuid was found");
            return "No Such Element found";
        }
        return importUG.getState().toString();
    }

    /**
     * Query Entity Manager for the error message generated for an import job.
     */
    @Override
    public String getErrorMessage(final UUID uuid) throws Exception {

        //get application entity manager

        if (uuid == null) {
            logger.error("getErrorMessage(): UUID passed in cannot be null.");
            return "UUID passed in cannot be null";
        }

        EntityManager rootEm = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);

        //retrieve the import entity.
        Import importUG = rootEm.get(uuid, Import.class);

        if (importUG == null) {
            logger.error("getErrorMessage(): no entity with that uuid was found");
            return "No Such Element found";
        }
        return importUG.getErrorMessage();
    }

    /**
     * Returns the Import Entity that stores all meta-data for the particular import Job
     *
     * @param jobExecution the import job details
     * @return Import Entity
     */
    @Override
    public Import getImportEntity(final JobExecution jobExecution) throws Exception {

        UUID importId = (UUID) jobExecution.getJobData().getProperty(IMPORT_ID);
        EntityManager importManager = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);

        return importManager.get( importId, Import.class );
    }


    /**
     * Returns the File Import Entity that stores all meta-data for the particular sub File import Job
     * @return File Import Entity
     */
    @Override
    public FileImport getFileImportEntity(final ImportQueueMessage queueMessage) throws Exception {

        EntityManager em = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);

        return em.get(queueMessage.getFileId(), FileImport.class);
    }


    /**
     * Returns the File Import Entity that stores all meta-data for the particular sub File import Job
     * @return File Import Entity
     */
    @Override
    public FileImport getFileImportEntity(final JobExecution jobExecution) throws Exception {

        UUID fileImportId = (UUID) jobExecution.getJobData().getProperty(FILE_IMPORT_ID);

        EntityManager em = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);

        return em.get(fileImportId, FileImport.class);
    }


    public SchedulerService getSch() {
        return sch;
    }


    public void setSch(final SchedulerService sch) {
        this.sch = sch;
    }


    public EntityManagerFactory getEmf() {
        return emf;
    }


    public void setEmf(final EntityManagerFactory emf) {
        this.emf = emf;
    }


    public ManagementService getManagementService() {

        return managementService;
    }


    public void setManagementService(final ManagementService managementService) {
        this.managementService = managementService;
    }


    /**
     * This method creates sub-jobs for each file i.e. File Import Jobs.
     *
     * @param jobExecution the job created by the scheduler with all the required config data
     */
    @Override
    public void doImport(JobExecution jobExecution) throws Exception {

        logger.debug("doImport()");

        Map<String, Object> config =
            (Map<String, Object>) jobExecution.getJobData().getProperty("importInfo");
        if (config == null) {
            logger.error("doImport(): Import Information passed through is null");
            return;
        }

        Map<String, Object> properties =
            (Map<String, Object>)config.get("properties");
        Map<String, Object> storage_info =
            (Map<String, Object>) properties.get("storage_info");

        String bucketName = (String) storage_info.get("bucket_location");
        String accessId = (String) storage_info.get( SDKGlobalConfiguration.ACCESS_KEY_ENV_VAR);
        String secretKey = (String) storage_info.get( SDKGlobalConfiguration.SECRET_KEY_ENV_VAR);

        // get Import Entity from the management app, update it to show that job has started

        EntityManager emManagementApp = emf.getEntityManager(CpNamingUtils.MANAGEMENT_APPLICATION_ID);
        UUID importId = (UUID) jobExecution.getJobData().getProperty(IMPORT_ID);
        Import rootImportTask = emManagementApp.get(importId, Import.class);

        rootImportTask.setState(Import.State.STARTED);
        rootImportTask.setStarted(System.currentTimeMillis());
        rootImportTask.setErrorMessage( " " );
        emManagementApp.update(rootImportTask);
        logger.debug("doImport(): updated state");

        // if no S3 importer was passed in then create one

        S3Import s3Import;
        Object s3PlaceHolder = jobExecution.getJobData().getProperty("s3Import");
        try {
            if (s3PlaceHolder != null) {
                s3Import = (S3Import) s3PlaceHolder;
            } else {
                s3Import = new S3ImportImpl();
            }
        } catch (Exception e) {
            logger.error("doImport(): Error creating S3Import", e);
            rootImportTask.setErrorMessage(e.getMessage());
            rootImportTask.setState( Import.State.FAILED );
            emManagementApp.update(rootImportTask);
            return;
        }

        // get list of all JSON files in S3 bucket

        final List<String> bucketFiles;
        try {

            if (config.get("organizationId") == null) {
                logger.error("doImport(): No organization could be found");
                rootImportTask.setErrorMessage( "No organization could be found" );
                rootImportTask.setState( Import.State.FAILED );
                emManagementApp.update(rootImportTask);
                return;

            } else {


                if (config.get("applicationId") == null) {
                    throw new UnsupportedOperationException("Import applications not supported");

                }  else {
                    bucketFiles = s3Import.getBucketFileNames( bucketName, ".json", accessId, secretKey );
                }
            }

        } catch (OrganizationNotFoundException | ApplicationNotFoundException e) {
            rootImportTask.setErrorMessage( e.getMessage() );
            rootImportTask.setState( Import.State.FAILED );
            emManagementApp.update(rootImportTask);
            return;
        }


        // schedule a FileImport job for each file found in the bucket

        if ( bucketFiles.isEmpty() )  {
            rootImportTask.setState( Import.State.FINISHED );
            rootImportTask.setErrorMessage( "No files found in the bucket: " + bucketName );
            emManagementApp.update(rootImportTask);

        } else {

            Map<String, Object> fileMetadata = new HashMap<>();
            ArrayList<Map<String, Object>> value = new ArrayList<>();
            final List<JobData> fileJobs = new ArrayList<>(bucketFiles.size());

            // create the Entity Connection and set up metadata for each job

            for ( String bucketFile : bucketFiles ) {
                final JobData jobData = createFileTask( config, bucketFile, rootImportTask );
                fileJobs.add( jobData) ;
            }

            // schedule each job

            for ( JobData jobData: fileJobs ) {

                final JobData scheduled = scheduleFileTasks( jobData );

                Map<String, Object> fileJobID = new HashMap<>();
                    fileJobID.put("FileName", scheduled.getProperty( "File" ));
                    fileJobID.put("JobID", scheduled.getUuid());
                value.add(fileJobID);
            }

            fileMetadata.put("files", value);
            rootImportTask.addProperties( fileMetadata );
            emManagementApp.update(rootImportTask);
        }
    }


    @Override
    public void downloadAndImportFile(JobExecution jobExecution) throws Exception {

        Map<String, Object> properties =
            (Map<String, Object>)jobExecution.getJobData().getProperty("properties");
        if (properties == null) {
            logger.error("downloadAndImportFile(): Import Information passed through is null");
            return;
        }
        Map<String, Object> storage_info =
            (Map<String, Object>) properties.get("storage_info");

        String bucketName = (String) storage_info.get("bucket_location");
        String accessId = (String) storage_info.get( SDKGlobalConfiguration.ACCESS_KEY_ENV_VAR);
        String secretKey = (String) storage_info.get( SDKGlobalConfiguration.SECRET_KEY_ENV_VAR);

        FileImport fileImport = getFileImportEntity(jobExecution);
        String fileName = jobExecution.getJobData().getProperty("File").toString();
        UUID targetAppId = (UUID) jobExecution.getJobData().getProperty("applicationId");

        logger.debug("downloadAndImportFile() for file {} ", fileName);

        if (   FileImport.State.FAILED.equals( fileImport.getState() )
            || FileImport.State.FINISHED .equals(fileImport.getState()) ) {
            return;
        }

        // update FileImport Entity to indicate that we have started

        EntityManager emManagementApp = emf.getEntityManager( CpNamingUtils.MANAGEMENT_APPLICATION_ID );
        emManagementApp.update( fileImport );
        fileImport.setState( FileImport.State.STARTED );
        emManagementApp.update( fileImport );

        if ( emManagementApp.get( targetAppId ) == null ) {
            throw new IllegalArgumentException( "Application does not exist: " + targetAppId.toString() );
        }
        EntityManager targetEm = emf.getEntityManager( targetAppId );

        // download file from S3, if no S3 importer was passed in then create one

        S3Import s3Import;
        Object s3PlaceHolder = jobExecution.getJobData().getProperty("s3Import");
        try {
            if (s3PlaceHolder != null) {
                s3Import = (S3Import) s3PlaceHolder;
            } else {
                s3Import = new S3ImportImpl();
            }
        } catch (Exception e) {
            logger.error("doImport(): Error creating S3Import", e);
            fileImport.setErrorMessage(e.getMessage());
            fileImport.setState( FileImport.State.FAILED );
            emManagementApp.update(fileImport);
            return;
        }
        File downloadedFile = s3Import.copyFileFromBucket(
            fileName, bucketName, accessId, secretKey );

        // parse JSON data, create Entities and Connections from import data

        parseEntitiesAndConnectionsFromJson(
            jobExecution, downloadedFile, targetEm, emManagementApp, fileImport);

        // mark ImportJob FINISHED but only if all other FileImportJobs are complete

        // get parent import job of this file import job
        Results importJobResults =
            emManagementApp.getConnectingEntities( fileImport, "includes", null, Level.ALL_PROPERTIES );
        List<Entity> importEntities = importJobResults.getEntities();
        UUID importId = importEntities.get( 0 ).getUuid();
        Import importEntity = emManagementApp.get( importId, Import.class );

        // get all file import job siblings of the current job we're working now
        Results entities = emManagementApp.getConnectedEntities( importEntity, "includes", null, Level.ALL_PROPERTIES );
        PagingResultsIterator itr = new PagingResultsIterator( entities );

        int failCount = 0;
        while ( itr.hasNext() ) {
            FileImport fi = ( FileImport ) itr.next();
            switch ( fi.getState() ) {
                case FAILED:     // failed, but we may not be complete so continue checking
                    failCount++;
                    break;
                case FINISHED:   // finished, we can continue checking
                    break;
                default:         // not something we recognize as complete, short circuit
                    return;
            }
        }

        if ( failCount == 0 ) {
            importEntity.setState(Import.State.FINISHED);
        }  else {
            // we had failures, set it to failed
            importEntity.setState(Import.State.FAILED);
        }

        emManagementApp.update( importEntity );
    }


    /**
     * Gets the JSON parser for given file
     *
     * @param collectionFile the file for which JSON parser is required
     */
    private JsonParser getJsonParserForFile(File collectionFile) throws Exception {
        JsonParser jp = jsonFactory.createJsonParser(collectionFile);
        jp.setCodec(new ObjectMapper());
        return jp;
    }


    /**
     * Imports the entity's connecting references (collections, connections and dictionaries)
     *
     * @param execution     The job jobExecution currently running
     * @param file         The file to be imported
     * @param em           Entity Manager for the application being imported
     * @param rootEm       Entity manager for the root applicaition
     * @param fileImport   The file import entity
     */
    private void parseEntitiesAndConnectionsFromJson(
        final JobExecution execution,
        final File file,
        final EntityManager em,
        final EntityManager rootEm,
        final FileImport fileImport) throws Exception {


        // tracker flushes every 100 entities
        final FileImportTracker tracker = new FileImportTracker( emf, fileImport, 100 );

        // function to execute for each write event
        final Action1<WriteEvent> doWork = new Action1<WriteEvent>() {
            @Override
            public void call( WriteEvent writeEvent ) {
                writeEvent.doWrite( em, fileImport, tracker );
            }
        };

        // invokes the heartbeat every HEARTBEAT_COUNT operations
        final Func2<Integer, WriteEvent, Integer> heartbeatReducer = new Func2<Integer, WriteEvent, Integer>() {
            @Override
            public Integer call( final Integer integer, final WriteEvent writeEvent ) {
                final int next = integer.intValue() + 1;
                if ( next % HEARTBEAT_COUNT == 0 ) {
                    execution.heartbeat();
                }
                return next;
            }
        };


        // FIRST PASS: import all entities in the file


        boolean entitiesOnly = true;

        // observable that parses JSON and emits write events
        JsonParser jp = getJsonParserForFile(file);

        // TODO: move JSON parser into observable creation so open/close happens within the stream
        final JsonEntityParserObservable jsonObservableEntities =
            new JsonEntityParserObservable(jp, em, rootEm, fileImport, entitiesOnly);
        final Observable<WriteEvent> entityEventObservable = Observable.create(jsonObservableEntities);

        // only take while our stats tell us we should continue processing
        // potentially skip the first n if this is a resume operation
        final int entityNumSkip = (int)tracker.getTotalEntityCount();

        // with this code we get asynchronous behavior and testImportWithMultipleFiles will fail
//       final int entityCount =  entityEventObservable.takeWhile( new Func1<WriteEvent, Boolean>() {
//            @Override
//            public Boolean call( final WriteEvent writeEvent ) {
//                return !tracker.shouldStopProcessingEntities();
//            }
//        } ).skip(entityNumSkip).parallel(new Func1<Observable<WriteEvent>, Observable<WriteEvent>>() {
//            @Override
//            public Observable<WriteEvent> call(Observable<WriteEvent> entityWrapperObservable) {
//                return entityWrapperObservable.doOnNext(doWork);
//            }
//        }, Schedulers.io()).reduce(0, heartbeatReducer).toBlocking().last();

        entityEventObservable.parallel(
            new Func1<Observable<WriteEvent>, Observable<WriteEvent>>() {
                @Override
                public Observable<WriteEvent> call(Observable<WriteEvent> entityWrapperObservable) {
                    return entityWrapperObservable.doOnNext(doWork);
                }
            }, Schedulers.io()).toBlocking().last();

        jp.close();

        logger.debug("\n\nparseEntitiesAndConnectionsFromJson(): Wrote entities\n");


        // SECOND PASS: import all connections and dictionaries


        entitiesOnly = false;

        // observable that parses JSON and emits write events
        jp = getJsonParserForFile(file);

        // TODO: move JSON parser into observable creation so open/close happens within the stream
        final JsonEntityParserObservable jsonObservableOther =
            new JsonEntityParserObservable(jp, em, rootEm, fileImport, entitiesOnly);

        final Observable<WriteEvent> otherEventObservable = Observable.create(jsonObservableOther);

        // only take while our stats tell us we should continue processing
        // potentially skip the first n if this is a resume operation
        final int connectionNumSkip = (int)tracker.getTotalConnectionCount();

        // with this code we get asynchronous behavior and testImportWithMultipleFiles will fail
//        final int connectionCount = otherEventObservable.takeWhile( new Func1<WriteEvent, Boolean>() {
//            @Override
//            public Boolean call( final WriteEvent writeEvent ) {
//                return !tracker.shouldStopProcessingConnections();
//            }
//        } ).skip(connectionNumSkip).parallel(new Func1<Observable<WriteEvent>, Observable<WriteEvent>>() {
//            @Override
//            public Observable<WriteEvent> call(Observable<WriteEvent> entityWrapperObservable) {
//                return entityWrapperObservable.doOnNext(doWork);
//            }
//        }, Schedulers.io()).reduce(0, heartbeatReducer).toBlocking().last();

       otherEventObservable.parallel(
            new Func1<Observable<WriteEvent>, Observable<WriteEvent>>() {
                @Override
                public Observable<WriteEvent> call(Observable<WriteEvent> entityWrapperObservable) {
                    return entityWrapperObservable.doOnNext(doWork);
                }
            }, Schedulers.io()).toBlocking().last();

        jp.close();

        logger.debug("\n\nparseEntitiesAndConnectionsFromJson(): Wrote others for file {}\n",
            fileImport.getFileName());


        // flush the job statistics
        tracker.complete();
    }


    private interface WriteEvent {
        public void doWrite(EntityManager em, FileImport fileImport, FileImportTracker tracker);
    }


    private final class EntityEvent implements WriteEvent {
        UUID entityUuid;
        String entityType;
        Map<String, Object> properties;

        EntityEvent(UUID entityUuid, String entityType, Map<String, Object> properties) {
            this.entityUuid = entityUuid;
            this.entityType = entityType;
            this.properties = properties;
        }



        // Creates entities
        @Override
        public void doWrite(EntityManager em, FileImport fileImport, FileImportTracker tracker) {
            try {
                logger.debug("Writing imported entity {}:{} into app {}",
                    new Object[]{entityType, entityUuid, em.getApplication().getUuid()});


                em.create(entityUuid, entityType, properties);

                tracker.entityWritten();

            } catch (Exception e) {
                logger.error("Error writing entity", e);

                tracker.entityFailed( e.getMessage() );
            }
        }
    }


    private final class ConnectionEvent implements WriteEvent {
        EntityRef ownerEntityRef;
        String connectionType;
        EntityRef entityRef;

        ConnectionEvent(EntityRef ownerEntityRef, String connectionType, EntityRef entryRef) {
            this.ownerEntityRef = ownerEntityRef;
            this.connectionType = connectionType;
            this.entityRef = entryRef;

        }

        // creates connections between entities
        @Override
        public void doWrite(EntityManager em, FileImport fileImport, FileImportTracker tracker) {

            try {
                // TODO: do we need to ensure that all Entity events happen first?
                // TODO: what happens if ConnectionEvents  happen before all entities are saved?

                // Connections are specified as UUIDs with no type
                if (entityRef.getType() == null) {
                    entityRef = em.get(ownerEntityRef.getUuid());
                }

                logger.debug("Creating connection from {}:{} to {}:{}",
                    new Object[]{
                        ownerEntityRef.getType(), ownerEntityRef.getUuid(),
                        entityRef.getType(), entityRef.getUuid()});

                em.createConnection(ownerEntityRef, connectionType, entityRef);

                tracker.connectionWritten();

            } catch (Exception e) {
                logger.error("Error writing connection", e);
                tracker.connectionFailed( e.getMessage() );
            }
        }
    }


    private final class DictionaryEvent implements WriteEvent {

        EntityRef ownerEntityRef;
        String dictionaryName;
        Map<String, Object> dictionary;

        DictionaryEvent(EntityRef ownerEntityRef, String dictionaryName, Map<String, Object> dictionary) {
            this.ownerEntityRef = ownerEntityRef;
            this.dictionaryName = dictionaryName;
            this.dictionary = dictionary;
        }

        // adds map to the dictionary
        @Override
        public void doWrite(EntityManager em, FileImport fileImport, FileImportTracker stats) {
            try {

                logger.debug("Adding map to {}:{} dictionary {}",
                    new Object[]{ownerEntityRef.getType(), ownerEntityRef.getType(), dictionaryName});

                em.addMapToDictionary(ownerEntityRef, dictionaryName, dictionary);

            } catch (Exception e) {

                //TODO add statistics for dictionary writes and failures
//                logger.error("Error writing dictionary", e);
//                fileImport.setErrorMessage(e.getMessage());
//                try {
//
//                    rootEm.update(fileImport);
//
//                } catch (Exception ex) {
//
//                    // TODO should we abort at this point?
//                    logger.error("Error updating file import report with error message: "
//                        + fileImport.getErrorMessage(), ex);
//                }
            }
        }
    }


    private final class JsonEntityParserObservable implements Observable.OnSubscribe<WriteEvent> {
        public static final String COLLECTION_OBJECT_NAME = "collections";
        private final JsonParser jp;
        EntityManager em;
        EntityManager rootEm;
        FileImport fileImport;
        boolean entitiesOnly;


        JsonEntityParserObservable(
            JsonParser parser,
            EntityManager em,
            EntityManager rootEm,
            FileImport fileImport,
            boolean entitiesOnly) {

            this.jp = parser;
            this.em = em;
            this.rootEm = rootEm;
            this.fileImport = fileImport;
            this.entitiesOnly = entitiesOnly;
        }


        @Override
        public void call(final Subscriber<? super WriteEvent> subscriber) {
            process(subscriber);
        }


        private void process(final Subscriber<? super WriteEvent> subscriber) {

            try {


                // we ignore imported entity type information, entities get the type of the collection
                Stack<JsonToken> objectStartStack = new Stack();
                Stack<String> objectNameStack = new Stack();
                EntityRef lastEntity = null;

//                String collectionName = null;
                String entityType = null;

                while ( true ) {


                    JsonToken token = jp.nextToken();

                    //nothing left to do.
                    if ( token == null ) {
                        break;
                    }

                    String name = jp.getCurrentName();


                    //start of an object with a field name

                    if ( token.equals( JsonToken.START_OBJECT ) ) {

                        objectStartStack.push( token );

                        //nothing to do
                        if ( name == null ) {
                            continue;
                        }


                        if ( "Metadata".equals( name ) ) {


                            Map<String, Object> entityMap = jp.readValueAs( HashMap.class );

                            UUID uuid = UUID.fromString( ( String ) entityMap.get( "uuid" ) );
                            lastEntity = new SimpleEntityRef( entityType, uuid );

                            if ( entitiesOnly ) {
                                //logger.debug("{}Got entity with uuid {}", indent, lastEntity);

                                WriteEvent event = new EntityEvent( uuid, entityType, entityMap );
                                subscriber.onNext( event );
                            }

                            objectStartStack.pop();
                        }
                        else if ( "connections".equals( name ) ) {


                            Map<String, Object> connectionMap = jp.readValueAs( HashMap.class );

                            for ( String type : connectionMap.keySet() ) {
                                List targets = ( List ) connectionMap.get( type );

                                for ( Object targetObject : targets ) {
                                    UUID target = UUID.fromString( ( String ) targetObject );

                                    if ( !entitiesOnly ) {
                                        //logger.debug("{}Got connection {} to {}",
                                        //new Object[]{indent, type, target.toString()});

                                        EntityRef entryRef = new SimpleEntityRef( target );
                                        WriteEvent event = new ConnectionEvent( lastEntity, type, entryRef );
                                        subscriber.onNext( event );
                                    }
                                }
                            }

                            objectStartStack.pop();
                        }
                        else if ( "dictionaries".equals( name ) ) {


                            Map<String, Object> dictionariesMap = jp.readValueAs( HashMap.class );
                            for ( String dname : dictionariesMap.keySet() ) {
                                Map dmap = ( Map ) dictionariesMap.get( dname );

                                if ( !entitiesOnly ) {
                                    //logger.debug("{}Got dictionary {} size {}",
                                    //new Object[] {indent, dname, dmap.size() });

                                    WriteEvent event = new DictionaryEvent( lastEntity, dname, dmap );
                                    subscriber.onNext( event );
                                }
                            }

                            objectStartStack.pop();
                        }
                        //push onto object names we don't immediately understand.  Used for parent detection
                        else{
                            objectNameStack.push( name );
                        }

                    }

                    else if (token.equals( JsonToken.START_ARRAY )){
                         if( objectNameStack.size() == 1 && COLLECTION_OBJECT_NAME.equals( objectNameStack.peek() )) {
                            entityType = InflectionUtils.singularize( name );
                         }
                    }

                    else if ( token.equals( JsonToken.END_OBJECT ) ) {
                        objectStartStack.pop();
                    }
                }

                subscriber.onCompleted();

                logger.debug("process(): done parsing JSON");

            } catch (Exception e) {
                // skip illegal entity UUID and go to next one
                fileImport.setErrorMessage(e.getMessage());
                try {
                    rootEm.update(fileImport);
                } catch (Exception ex) {
                    logger.error("Error updating file import record", ex);
                }
                subscriber.onError(e);
            }
        }
    }
}


/**
 * Custom Exception class for Organization Not Found
 */
class OrganizationNotFoundException extends Exception {
    OrganizationNotFoundException(String s) {
        super(s);
    }
}


/**
 * Custom Exception class for Application Not Found
 */
class ApplicationNotFoundException extends Exception {
    ApplicationNotFoundException(String s) {
        super(s);
    }
}