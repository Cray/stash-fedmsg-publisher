package com.cray.stash;

import com.atlassian.stash.pull.PullRequest;
import com.atlassian.stash.pull.PullRequestParticipant;
import com.atlassian.stash.pull.PullRequestRef;
import com.atlassian.stash.pull.PullRequestState;
import org.mockito.Mock;
import static org.mockito.Mockito.*;
import java.util.Date;
import java.util.Set;

public abstract class MockPullRequest implements PullRequest {

    // Inherited class variables for PullRequest
    @Mock
    private PullRequestParticipant mockAuthor;
    //@Mock
    //private Date mockDate;
    //@Mock
    //private String mockDescription;
    @Mock
    private PullRequestRef mockFromRef;
    @Mock
    private PullRequestRef mockToRef;
    @Mock
    private Long mockId;
    @Mock
    private Set<PullRequestParticipant> mockParticipants;
    @Mock
    private Set<PullRequestParticipant> mockReviewers;
    @Mock
    private PullRequestState mockState;
    @Mock
    private String mockTitle;
    @Mock
    private Date mockUpdatedDate;
    @Mock
    private int mockVersion;
    @Mock
    private boolean mockClosed;
    @Mock
    private boolean mockOpened;
    @Mock
    private boolean mockLocked;
    @Mock
    private boolean mockCrossRepository;

    //constructor
    public MockPullRequest() {}

        public PullRequestParticipant getAuthor() {
            when(mockAuthor.getUser().getDisplayName()).thenReturn("Rick Jameson");
            when(mockAuthor.getUser().getEmailAddress()).thenReturn("rick.jameson@cray.com");
            return mockAuthor;
        }

        public Date getCreatedDate() {
            Date date = new Date();
            return date;
        }

        public String getDescription() {
            return "This is a unit test description";
        }

        public PullRequestRef getFromRef() {

            return mockFromRef;
        }

        public PullRequestRef getToRef() {
            return mockToRef;
        }

        public Long getId() {
            return mockId;
        }

        public Set<PullRequestParticipant> getParticipants() {
            return mockParticipants;
        }

        public Set<PullRequestParticipant> getReviewers() {
            return mockReviewers;
        }

        public PullRequestState getState() {
            return mockState;
        }

        public String getTitle() {
            return mockTitle;
        }

        public Date getUpdatedDate() {
            return mockUpdatedDate;
        }

        public int getVersion() {
            return mockVersion;
        }

        public boolean isClosed() {
            return mockClosed;
        }

        public boolean isOpen() {
            return mockOpened;
        }

        public boolean isLocked() {
            return mockLocked;
        }

        public boolean isCrossRepository() {
            return mockCrossRepository;
        }
}