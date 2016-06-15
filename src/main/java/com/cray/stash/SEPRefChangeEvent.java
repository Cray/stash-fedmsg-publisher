package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import org.fedoraproject.fedmsg.FedmsgConnection;

import java.util.ArrayList;

/**
 * Created by swalter on 6/9/2016.
 */
public interface SEPRefChangeEvent {
    void processEvent(RepositoryRefsChangedEvent event);
    void sendCommits(ArrayList<Message> commitMessages);
}
