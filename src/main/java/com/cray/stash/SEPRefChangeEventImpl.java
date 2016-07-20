package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.RefChangeType;
import com.atlassian.stash.repository.Repository;
import com.atlassian.stash.server.ApplicationPropertiesService;
import org.fedoraproject.fedmsg.FedmsgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;


/**
 * Created by swalter on 5/18/2016.
 */
public class SEPRefChangeEventImpl implements SEPRefChangeEvent {

    private static final Logger LOGGER = LoggerFactory.getLogger(SEPRefChangeEventImpl.class);
    private SEPCommits sepCommits;
    private FedmsgConnection connection;
    private String endpoint;
    private static final String REF_BRANCH = "refs/heads";
    private static final String REF_TAG = "refs/tags";
    public SEPRefChangeEventImpl(SEPCommits sepCommits, ApplicationPropertiesService appService) {
        this.sepCommits = sepCommits;

        try {
            endpoint = appService.getPluginProperty("plugin.fedmsg.events.relay.endpoint");
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve properties\n" + e);
        }

        if (endpoint == null) {
            endpoint = "tcp://bit01.us.cray.com:9941";
            LOGGER.info("The endpoint value was not set. Using the default bit01 relay.");
        }
    }

    @Override
    public void processEvent(RepositoryRefsChangedEvent event) {
        try {
            LOGGER.info("Establishing  connection to relay.");
            connection = new FedmsgConnection(endpoint, 2000).connect();
        } catch (Exception e) {
            LOGGER.error("Failed to connect to relay\n" + e);
        }

        for (RefChange refChange : event.getRefChanges()) {
            LOGGER.info("checking ref change refId={} fromHash={} toHash={} type={}", refChange.getRefId(), refChange.getFromHash(),
                    refChange.getToHash(), refChange.getType());

            if (refChange.getRefId().startsWith("refs/notes")) {
                LOGGER.info("Skipping git notes.");
            } else if (refChange.getType() == RefChangeType.ADD && isDeleted(refChange)) {
                LOGGER.info("Deleted a ref that never existed. This shouldn't ever occur.");
            } else if(refChange.getRefId().startsWith(REF_BRANCH) && (isDeleted(refChange) || isCreated(refChange))){
                branchCreation(refChange, event.getRepository());
            } else if(refChange.getRefId().startsWith(REF_TAG)) {
                //tagCreation(refChange, event.getRepository());
            } else if(!refChange.getRefId().startsWith(REF_BRANCH) && !refChange.getRefId().startsWith(REF_TAG)) {
                //bizarre weird ref name
                LOGGER.info("Unexpected refChange name: {}. Did not process.", refChange.getRefId());
            } else {
                sendCommits(sepCommits.findCommitInfo(refChange, event.getRepository()));
            }
        }
    }

    public void branchCreation(RefChange refChange, Repository repo){
        if(isCreated(refChange)){
            LOGGER.info("Branch Creation event occurred. Possible new commits on this branch.");
            sendCommits(sepCommits.findCommitInfo(refChange, repo));
        } else if(isDeleted(refChange)){
            //not supported yet
        } else{
            LOGGER.info("Encountered a branch related refChange event that wasn't accounted for: " +
                        "refId={} fromHash={} toHash={} type={}", refChange.getRefId(), refChange.getFromHash(),
                         refChange.getToHash(), refChange.getType());
        }
    }

    public void tagCreation(RefChange ref, Repository repo){
        //stub
    }

    @Override
    public void sendCommits(List<Message> commitMessages) {
        try {
            for(Message message: commitMessages){
                message.sendMessage(connection);
            }
        } catch (Exception e) {
            LOGGER.error("Exception was caught while sending commit info to fedmsg\n" + e);
        }

        LOGGER.info("Disconnecting from relay.");
        try {
            connection.disconnect();
        } catch (Exception e)
        {
            LOGGER.error("Error while disconnecting from the fedmsg relay.");
        }
    }

    /*
    * These are helper methods to determine if a branch is newly created or deleted.
    */
    private boolean isCreated(RefChange ref) {
        return ref.getFromHash().contains("0000000000000000000000000000000000000000");
    }

    private boolean isDeleted(RefChange ref) {
        return ref.getToHash().contains("0000000000000000000000000000000000000000");
    }
}
