package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import com.atlassian.stash.repository.RefChange;
import com.atlassian.stash.repository.RefChangeType;
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

    public SEPRefChangeEventImpl(SEPCommits sepCommits, ApplicationPropertiesService appService) {
        this.sepCommits = sepCommits;

        try {
            endpoint = appService.getPluginProperty("plugin.fedmsg.events.relay.endpoint");
        } catch (Exception e) {
            LOGGER.error("Failed to retrieve properties " + e.getMessage());
        }

        if (endpoint == null) {
            endpoint = "tcp://bit01.us.cray.com:9941";
            LOGGER.info("The endpoint value was not set. Using the default bit01 relay.");
        }

        try {
            connection = new FedmsgConnection(endpoint, 2000).connect();
        } catch (Exception e) {
            LOGGER.error("Failed to connect to relay: " + e.getMessage());
        }
    }

    @Override
    public void processEvent(RepositoryRefsChangedEvent event) {

        for (RefChange refChange : event.getRefChanges()) {
            LOGGER.info("checking ref change refId={} fromHash={} toHash={} type={}", refChange.getRefId(), refChange.getFromHash(),
                    refChange.getToHash(), refChange.getType());

            if (refChange.getRefId().startsWith("refs/notes")) {
                LOGGER.info("Skipping git notes.");
                continue;
            }

            if (refChange.getType() == RefChangeType.ADD && isDeleted(refChange)) {
                LOGGER.info("Deleted a ref that never existed. This shouldn't ever occur.");
                continue;
            }

            if (isCreated(refChange) || isDeleted(refChange)) {
                if (refChange.getRefId().startsWith("refs/heads")) {
                    if (isCreated(refChange)) {
                        LOGGER.info("Branch Creation event occurred. Possible new commits on this branch.");
                        //sendBranchChangeMessage("created", refChange, repository);
                        sendCommits(sepCommits.findCommitInfo(refChange, event.getRepository()));
                    }
                    else if (isDeleted(refChange)) {
                        //LOGGER.info("Branch Deletion event occurred.");
                        //sendBranchChangeMessage("deleted", refChange, repository);
                        continue;
                    }
                }
                else if (refChange.getRefId().startsWith("refs/tags")) {
                    if (isCreated(refChange)) {
                        //LOGGER.info("Tag Creation event occurred.");
                        //sendTagMessage("created", refChange, repository, latestRefIds);
                        continue;
                    }
                    else if (isDeleted(refChange)) {
                        //LOGGER.info("Tag deletion event occurred.");
                        //sendTagMessage("deleted", refChange, repository, latestRefIds);
                        continue;
                    }
                }
                else {
                    LOGGER.info("Unexpected refChange name: {}", refChange.getRefId());
                }
            }
            else {
                //sanity check?
                if(refChange.getRefId().startsWith("refs/heads")){
                    sendCommits(sepCommits.findCommitInfo(refChange, event.getRepository()));
                } else {
                    LOGGER.info("Found new commits that were using an unexpected ref name: {}\nDid not process them.",refChange.getRefId());
                }
            }
        }
    }

    @Override
    public void sendCommits(List<Message> commitMessages) {
        try {
            for(Message message: commitMessages){
                message.sendMessage(connection);
            }
        } catch (Exception e) {
            LOGGER.error("Exception was caught while sending commit info to fedmsg: " + e.getMessage());
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
