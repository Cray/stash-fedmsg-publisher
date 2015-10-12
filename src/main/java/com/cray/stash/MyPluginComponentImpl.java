package com.cray.stash;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.stash.event.pull.*;
import org.mockito.Mock;
import static org.mockito.Mockito.*;

public class MyPluginComponentImpl implements MyPluginComponent
{
    private final ApplicationProperties applicationProperties;
    @Mock private PullRequestEvent event;
    @Mock private PullRequestOpenedEvent opened;
    @Mock private PullRequestMergedEvent merged;
    @Mock private PullRequestDeclinedEvent declined;
    @Mock private PullRequestApprovalEvent statusChange;
    @Mock private PullRequestRolesUpdatedEvent reviewersChange;
    @Mock private FedmsgEventListener eventListener;

    public MyPluginComponentImpl(ApplicationProperties applicationProperties)
    {
        this.applicationProperties = applicationProperties;
    }

    public String getName()
    {
        if(null != applicationProperties)
        {
            return "myComponent:" + applicationProperties.getDisplayName();
        }
        
        return "myComponent";
    }

}
