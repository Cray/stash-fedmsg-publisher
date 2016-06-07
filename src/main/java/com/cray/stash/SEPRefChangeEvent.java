package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import org.fedoraproject.fedmsg.FedmsgConnection;

import java.util.ArrayList;

/**
 * Created by swalter on 6/2/2016.
 */
public interface SEPRefChangeEvent {
    public void processEvent(RepositoryRefsChangedEvent event, FedmsgConnection connection);
    public void sendCommits(ArrayList<Message> commitMessages, FedmsgConnection connection);
}
