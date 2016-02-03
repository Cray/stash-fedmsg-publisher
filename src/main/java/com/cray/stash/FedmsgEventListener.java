package com.cray.stash;

import com.atlassian.event.api.EventListener;
import com.atlassian.stash.commit.Changeset;
import com.atlassian.stash.commit.CommitService;
import com.atlassian.stash.content.*;
import com.atlassian.stash.commit.*;
import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import com.atlassian.stash.event.pull.*;
import com.atlassian.stash.exception.AuthorisationException;
import com.atlassian.stash.pull.PullRequest;
import com.atlassian.stash.pull.PullRequestAction;
import com.atlassian.stash.pull.PullRequestParticipant;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.user.StashUser;
import com.atlassian.stash.util.NamedLink;
import com.atlassian.stash.util.Page;
import com.atlassian.stash.util.PageUtils;
import com.atlassian.stash.server.ApplicationPropertiesService;

// Sending fedmsg messages
import org.fedoraproject.fedmsg.FedmsgConnection;
import org.fedoraproject.fedmsg.FedmsgMessage;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;

// Sending Emails regarding exceptions
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class FedmsgEventListener {
    private CommitService commitService;
    private String endpoint;
    private FedmsgConnection connect;
    private RefService repoData;
    private RepositoryService repoService;
    private String topicPrefix;
    private int pageLimit;
    //private static final Logger log = LoggerFactory.getLogger(FedmsgEventListener.class);

    public FedmsgEventListener(CommitService commitService, RefService repoData, RepositoryService repoService, ApplicationPropertiesService appService) {
        //log.info("Initializing FedmsgEventListerner plugin...");
        this.commitService = commitService;
        this.repoData = repoData;
        this.repoService = repoService;
        //This is the address of the relay_inbound
        try {
            endpoint = appService.getPluginProperty("plugin.fedmsg.events.relay.endpoint");
            //log.info("Relay endpoint: " + endpoint);
            // The connection to that endpoint
            topicPrefix = appService.getPluginProperty("plugin.fedmsg.events.topic.prefix");
            //log.info("Topic prefix: " + topicPrefix);
            pageLimit = Integer.parseInt(appService.getPluginProperty("plugin.fedmsg.pageLimit"));
        } catch (Exception e) {
            sendMail("Failed to retrieve properties " + e.getMessage());
        }

        try {
            this.connect = new FedmsgConnection(endpoint, 2000).connect();
            //log.info("FedmsgEventListener plugin initialized successfully!");
        } catch (Exception e) {
            sendMail("Failed to connect to fedmsg relay" + e.getMessage());
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

    /*
     * This method takes in a repository and returns a set of the latest ref ids for all of
     * its branches except for the one we're currently analyzing. That one could have new
     * commits on it and we don't want to miss those.
     */
    private HashSet<String> getLatestRefs(Repository repo, RefChange ref) {
        final RepositoryBranchesRequest branchesRequest = new RepositoryBranchesRequest.Builder(repo).build();
        Page<Branch> branches = repoData.getBranches(branchesRequest, PageUtils.newRequest(0, 100));
        HashSet<String> refIds = new HashSet<String>(branches.getSize());
        try {
            for (Branch branch : branches.getValues()) {
                if (!branch.getId().contentEquals(ref.getRefId())) {
                    refIds.add(branch.getLatestCommit());
                }
            }
        } catch(Exception e) {
            sendMail("An error occurred while finding all the latest refIds for each branch in a repo " + e.getMessage());
        }
        //log.info("Found the latest refs: " + refIds);
        return refIds;
    }

    /*
     * This is a helper method for all events, it simply sends the message to Fedmsg with
     * a specified topic and prepends an topic prefix, environment, and modname.
     */
    private void sendMessage(Message message) {
        //log.info("Sending fedmsg message...");
        FedmsgMessage msg = new FedmsgMessage(
                message.getMessage(),
                (topicPrefix + message.getTopic()).toLowerCase(),
                (new java.util.Date()).getTime() / 1000,
                1);
        try {
            connect.send(msg);
        } catch (IOException e) {
            sendMail("Encountered IOException when sending a fedmsg msg" + e);
        } catch (Exception e) {
            sendMail("Encountered an error when sending a fedmsg msg" + e);
        }
    }
    /*
     *  This method is used to send email to ci-info in the event that an exception was
     *  encountered somewhere in the plugin.
     */
    public void sendMail(String email) {
        String to = "ci-info@cray.com";
        String from = "build@cray.com";
        String host = "relaya.us.cray.com";
        Properties properties = System.getProperties();
        properties.setProperty("mail.smtp.host", host);
        Session session = Session.getDefaultInstance(properties);
        try {
            // Create a default MimeMessage object.
            MimeMessage message = new MimeMessage(session);

            // Set From: header field of the header.
            message.setFrom(new InternetAddress(from));

            // Set To: header field of the header.
            message.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(to));

            // Set Subject: header field
            message.setSubject("The Stash Plugin Has encountered an error.");

            // Now set the actual message
            message.setText(email);

            // Send message
            Transport.send(message);
            System.out.println("Sent message successfully....");
        } catch (MessagingException mex) {
            mex.printStackTrace();
        }
    }

    /*
     * This method signals the creation/deletion of a branch event and sends a Fedmsg message regarding that.
     */
    private void sendBranchChangeMessage(String state, RefChange ref, Repository repo) {
        String topic = repo.getProject().getKey() + "." + repo.getName() + ".branch." + state;
        HashMap<String, Object> content = new HashMap<String, Object>();
        try {
            content.put("repository", repo.getName());
            content.put("project_key", repo.getProject().getKey());
            content.put("branch", ref.getRefId());
            content.put("urls", getCloneUrls(repo));
            content.put("state", state);
            repo.getProject().getKey();

        } catch (Exception e)
        {
            sendMail("Error while scraping for branch change information " + e.getMessage());
        }
        Message message = new Message(content, topic);
        sendMessage(message);
    }

    /*
     * This method processes the creation/deletion of a tag and sends a Fedmsg message regarding that.
     */
    private void sendTagMessage(String state, RefChange ref, Repository repo, HashSet<String> latestRefIds)
    {
        String topic = repo.getProject().getKey() + "." + repo.getName() + ".tag." + state;
        HashMap<String, Object> content = new HashMap<String, Object>();
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(tz);
        ArrayList<Commit> tagCommits = preProcessChangeset(repo, ref, latestRefIds);

        try {
            content.put("repository", repo.getName());
            content.put("project_key", repo.getProject().getKey());
            content.put("tag", ref.getRefId());
            content.put("urls", getCloneUrls(repo));
            content.put("revision", ref.getToHash());
            content.put("state", state);
            if(tagCommits.size() > 0) {
                content.put("author", tagCommits.get(0).getAuthor());
                content.put("tag message", tagCommits.get(0).getMessage());
                content.put("when_timestamp", df.format(tagCommits.get(0).getAuthorTimestamp()));
            }
        } catch(Exception e) {
            sendMail("Error while scraping for tag change information " + e.getMessage());
        }
        Message message = new Message(content, topic);
        sendMessage(message);
    }

    /*
     * This method gets commit(s) in between two refs.
     */
    private Page<Commit> getChangeset(Repository repository, RefChange refChange) {
        CommitsBetweenRequest.Builder commitsRequest = new CommitsBetweenRequest.Builder(repository);
        commitsRequest.exclude(refChange.getFromHash());
        commitsRequest.include(refChange.getToHash());
        return commitService.getCommitsBetween(commitsRequest.build(), PageUtils.newRequest(0, pageLimit));
    }

    /*
     * This method figures out which commits are already upstream (as best as it can) and then copies
     * the new commits to an array list that it returns for usage in processChanges(...). This method
     * exists for when someone creates a branch with new commits on it and pushes that new branch
     * upstream.
     */
    private ArrayList<Commit> preProcessChangeset(Repository repo, RefChange ref, HashSet<String> latestRefIds) {
        //log.info("Finding latest commits on given ref.");
        // List of messages to send across the fedmsg bus that have not already been processed
        ArrayList<Commit> newCommits = new ArrayList<Commit>();

        // Page of all commits, likely containing ones we've already processed
        Page<Commit> commits = getChangeset(repo, ref);
        // Process commits newest to oldest
        try {
            for (Commit commit : commits.getValues()) {
                if (latestRefIds.contains(commit.getId())) {
                    break;
                } else {
                    newCommits.add(commit);
                }
            }
        } catch(Exception e) {
            sendMail("Error while finding new commits from a refChange " + e.getMessage());
        }
        //log.info("Found the latest commits: " + newCommits);
        return newCommits;
    }

    /*
     * Returns a set of the various links (http, ssh) that a particular scm (git, svn) can clone from.
     */
    private HashMap<String, String> getCloneUrls(Repository repo) {
        final RepositoryCloneLinksRequest linksRequest = new RepositoryCloneLinksRequest.Builder()
                .repository(repo)
                .build();
        HashMap<String, String> links = new HashMap<String, String>(2);
        try {
            Set<NamedLink> setLinks = repoService.getCloneLinks(linksRequest);
            for(NamedLink link: setLinks) {
                if(link.getHref().contains("https://")) {
                    links.put(link.getName() + "_url", "https://" + link.getHref().substring(link.getHref().indexOf("@") + 1));
                }
                else if (link.getHref().contains("http://"))
                {
                    links.put(link.getName() + "_url", "http://" + link.getHref().substring(link.getHref().indexOf("@") + 1));
                }
                else {
                    links.put(link.getName() + "_url", link.getHref());
                }
            }
        } catch(AuthorisationException e) {
            sendMail("An authorisation error occurred " + e.getMessage());
        } catch(Exception e){
            sendMail("An error occurred while getting  clone urls " + e.getMessage());
        }
        return links;
    }

    /*
     * This method takes an individual commit object, and extracts the information from it that we want to send to
     * Fedmsg. This method is for use with the pushEvent method, so strictly refChanges.
     */
    private HashMap<String, Object> refChangeExtracter(Commit commit, ArrayList<String> files, String branch)
    {
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(tz);

        HashMap<String, Object> content = new HashMap<String, Object>();
        HashMap<String, String> author = new HashMap<String, String>(2);
        try {
            author.put("name", commit.getAuthor().getName());
            author.put("emailAddress", commit.getAuthor().getEmailAddress());
            content.put("author", author);
            content.put("comments", commit.getMessage());
            content.put("project_key", commit.getRepository().getProject().getKey());
            content.put("urls", getCloneUrls(commit.getRepository()));
            content.put("repository", commit.getRepository().getName());
            content.put("project", commit.getRepository().getProject().getName());
            content.put("revision", commit.getId());
            content.put("when_timestamp", df.format(commit.getAuthorTimestamp()));
            content.put("branch", branch);
            content.put("files", files);
        } catch (Exception e) {
            sendMail("An error occured while extracing information from a refchange " + e.getMessage());
        }
        return content;
    }

    /*
     * This method takes an ArrayList of commits (in between two ref Changes) and constructs a list of
     * message to send. All commits in the ArrayList should be new commits.
     */
    private ArrayList<Message> processChanges(ArrayList<Commit> commits, RefChange ref)
    {
        //log.info("Collecting relevant information for  given refChange event");
        //list of messages to send across the fedmsg bus
        ArrayList<HashMap<String, Object>> toSend = new ArrayList<HashMap<String, Object>>();

        try {
            // Parsing backwards through the new commits
            for (int i = 0; i < commits.size(); i++) {
                // This is the request to grab the change data, which is where we find the file path info
                final ChangesetsRequest.Builder changesRequestBuilder = new ChangesetsRequest.Builder(commits.get(i).getRepository());
                ChangesetsRequest changesRequest = changesRequestBuilder.commitIds(commits.get(i).getId()).build();
                final Page<Changeset> page = commitService.getChangesets(changesRequest, PageUtils.newRequest(0, pageLimit));

                ArrayList<String> filesChanged = new ArrayList<String>();
                for (Changeset change : page.getValues()) {
                    for (Change files : change.getChanges().getValues()) {
                        filesChanged.add(files.getPath().toString());
                    }
                }
                toSend.add(refChangeExtracter(commits.get(i), filesChanged, ref.getRefId().substring(11)));
            }

        } catch (NullPointerException e){
            sendMail("A NullPointerException occurred while processing changes " + e.getMessage());
        } catch(Exception e){
            sendMail("An error occurred while parsing new commits " + e.getMessage());
        }

        ListIterator<HashMap<String, Object>> li = toSend.listIterator(toSend.size());
        ArrayList<Message> messageList = new ArrayList<Message>(toSend.size());
        try {
            while (li.hasPrevious()) {
                HashMap<String, Object> sending = li.previous();
                String topic = sending.get("project_key").toString() + "." + sending.get("repository").toString() + ".commit";
                Message message = new Message(sending, topic);
                sendMessage(message);
                messageList.add(message);
            }
        } catch(Exception e){
            sendMail("An error occurred while sending all the new commits to fedmsg " + e.getMessage());
        }
        return messageList;
    }

    /*
     * This method handles all of the different types of events that might occur when an refChangeEvent happens.
     * This could be merges, pushes, new branches, or tag creations.
     */
    @EventListener
    public void refChangeListener(RepositoryRefsChangedEvent event) {
        //log.info("RefChange event occurred.");
        try {
            Collection<RefChange> refChanges = event.getRefChanges();
            Repository repository = event.getRepository();

            for (RefChange refChange : refChanges) {
                HashSet<String> latestRefIds = getLatestRefs(repository, refChange);

                // Are we dealing with a new tag/branch or just normal commits?
                if (isCreated(refChange) || isDeleted(refChange)) {
                    // Are we dealing with a tag/branch refChange?
                    if (!refChange.getRefId().contains("refs/tags")) {
                        // Branch creation
                        if (isCreated(refChange)) {
                            //log.info("Branch Creation event occurred.");
                            sendBranchChangeMessage("created", refChange, repository);
                            processChanges(preProcessChangeset(repository, refChange, latestRefIds), refChange);
                        }
                        // Branch deletion
                        else if (isDeleted(refChange)) {
                            //log.info("Branch Deletion event occurred.");
                            sendBranchChangeMessage("deleted", refChange, repository);
                        }
                    }
                    // Tag refChange
                    else if (refChange.getRefId().contains("refs/tags")) {
                        // Tag creation
                        if (isCreated(refChange)) {
                            //log.info("Tag Creation event occurred.");
                            sendTagMessage("created", refChange, repository, latestRefIds);
                        }
                        // Tag deletion
                        else if (isDeleted(refChange)) {
                            //log.info("Tag deletion event occurred.");
                            sendTagMessage("deleted", refChange, repository, latestRefIds);
                        }
                    }
                }
                // This is just new commits (or a merge commit), no tags or branches.
                else {
                    processChanges(preProcessChangeset(repository, refChange, latestRefIds), refChange);
                }
            }
        } catch (Exception e){
            sendMail("An error was produced by the refChange listener " + e.getMessage());
        }
    }

    /*
     * This method extracts all the relevant information from a pull request event and packages it into
     * a HashMap for usage with the Fedmsg commands. It is similar to the refChangeExtracter.
     */
    public HashMap<String, Object> prExtracter (PullRequestEvent event)
    {
        //log.info("Collecting relevant information for Pull Request event.");
            PullRequest pr = event.getPullRequest();
        // Time format bits.
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        df.setTimeZone(tz);

        // Extract information from the PR event
        HashMap<String, String> author = new HashMap<String, String>(2);
        HashMap<String, Object> message = new HashMap<String, Object>();
        try {
            author.put("name", pr.getAuthor().getUser().getDisplayName());
            author.put("emailAddress", pr.getAuthor().getUser().getEmailAddress());

            Repository originRepo = pr.getFromRef().getRepository();
            String originProject = pr.getFromRef().getRepository().getProject().getName();
            Repository destRepo = pr.getToRef().getRepository();
            String destProject = pr.getToRef().getRepository().getProject().getName();
            String originProjectKey = pr.getFromRef().getRepository().getProject().getKey();
            String destProjectKey = pr.getToRef().getRepository().getProject().getKey();
            Long id = pr.getId();
            String desc = pr.getDescription();
            Date date = pr.getCreatedDate();
            String state = pr.getState().name();
            Set<PullRequestParticipant> reviewers = pr.getReviewers();

            // Creating the source branch sub dictionary
            HashMap<String, Object> source = new HashMap<String, Object>();
            source.put("urls", getCloneUrls(originRepo));
            source.put("repository", originRepo.getName());
            source.put("project", originProject);
            source.put("branch", pr.getFromRef().getDisplayId());
            source.put("project_key", originProjectKey);

            // Creating the destination branch dictionary
            HashMap<String, Object> destination = new HashMap<String, Object>();
            destination.put("urls", getCloneUrls(destRepo));
            destination.put("repository", destRepo.getName());
            destination.put("project", destProject);
            destination.put("branch", pr.getToRef().getDisplayId());
            destination.put("project_key", destProjectKey);

            // Construct the body of the fedmsg message
            message.put("author", author);
            message.put("when_timestamp", df.format(date));
            message.put("source", source);
            message.put("destination", destination);
            message.put("id", id);
            message.put("description", desc);
            message.put("state", state);
            ArrayList<String> reviewerList = new ArrayList<String>(reviewers.size());
            if (!reviewers.isEmpty()) {
            /* "PullRequestParticipants" are not serializable by JSON (in order to send messages
             * via Fedmsg, the contents of the message must be able to be encoded by JSON)
             * so we need to create a new list of just strings for each reviewer.
             */
                for (PullRequestParticipant person : reviewers) {
                    reviewerList.add(person.getUser().getDisplayName());
                }
                message.put("reviewers", reviewerList);

            }
        } catch(Exception e) {
            sendMail("An exception occurred while extracting information from a pull request event " + e.getMessage());
        }
        return message;
    }

    public String getProjectKey(PullRequestEvent event)
    {
        PullRequest pr = event.getPullRequest();
        return pr.getFromRef().getRepository().getProject().getKey();
    }

    public String getRepoName(PullRequestEvent event)
    {
        PullRequest pr = event.getPullRequest();
        return pr.getFromRef().getRepository().getName();
    }
    /*
     * This method is for constructing the basic pull request event message. It extracts all of the
     * necessary information from the event, and creates a Message object to then send via fedmsg.
     */
    public Message getMessage(PullRequestEvent event, String type)
    {
        HashMap<String, Object> content = prExtracter(event);
        String originProjectKey = getProjectKey(event);
        String originRepo = getRepoName(event);
        return new Message(content, originProjectKey + "." + originRepo + type);
    }

    /*
     * This getMessage method handles when the approval status changes. Depending on the event
     * the topic needs to change and also the content of the message.
     */
    public Message getMessage(PullRequestApprovalEvent event)
    {
        PullRequestAction action = event.getAction();

        HashMap<String, Object> content = prExtracter(event);
        String originProjectKey = getProjectKey(event);
        String originRepo = getRepoName(event);
        String topic = "";
        if(action == PullRequestAction.APPROVED){
            topic = originProjectKey + "." + originRepo + ".pullrequest.approved";
            content.put("approver", event.getParticipant().getUser().getDisplayName());
        }
        else if(action == PullRequestAction.UNAPPROVED) {
            topic = originProjectKey + "." + originRepo + ".pullrequest.unapproved";
            content.put("disprover", event.getParticipant().getUser().getDisplayName());
        }
        return new Message(content, topic);
    }

    /*
     * This getMessage method handles specific reviewersmodified event. It needs to
     * extract a list of reviewers added and removed and append that to the message.
     */
    public Message getMessage(PullRequestRolesUpdatedEvent event, String type)
    {
        Set<StashUser> added = event.getAddedReviewers();
        Set<StashUser> removed = event.getRemovedReviewers();

        HashMap<String, Object> content = prExtracter(event);
        String originProjectKey = getProjectKey(event);
        String originRepo = getRepoName(event);

        if(!added.isEmpty()) {
            ArrayList<String> addedList = new ArrayList<String>(added.size());
            for(StashUser user: added) {
                addedList.add(user.getDisplayName());
            }
            content.put("reviewers_added", addedList);
        }
        if(!removed.isEmpty()) {
            ArrayList<String> removedList = new ArrayList<String>(removed.size());
            for(StashUser user: removed) {
                removedList.add(user.getDisplayName());
            }
            content.put("reviewers_removed", removedList);
        }

        String topic = originProjectKey + "." + originRepo + type;
        Message message = new Message(content, topic);
        return message;
    }

    /*
     * The following event handlers handles all of the pull requests events we care about, currently:
     * merged, declined, approved, opened, and unapproved
     */

    /*
     * This event fires when someone hits the "merge" button on an open pull request. It sends a Fedmsg
     * message to the relevant topic about this event.
     */
    @EventListener
    public void merged(PullRequestMergedEvent event) {
        //log.info("Pull Request Merged Event occurred.");
        try {
            Message message = getMessage(event, ".pullrequest.merged");
            sendMessage(message);
        } catch(Exception e) {
            sendMail("An error occurred while handling a merged pull request event " + e.getMessage());
        }
    }

    /*
     * This event fires when someone opens a pull request and sends a Fedmsg message to a relevant topic
     * about the event.
     */
    @EventListener
    public void opened(PullRequestOpenedEvent event) {
        //log.info("Pull Request Opened Event occurred.");
        try {
            Message message = getMessage(event, ".pullrequest.opened");
            sendMessage(message);
        } catch(Exception e) {
            sendMail("An error occurred while handling an opened pull request event " + e.getMessage());
        }
    }

    /*
     * This event fires if someone declines the pull request. It also sends a Fedmsg message to the relevant
     * topic. For some reason, this event doesn't store who did the approving, so that is not part of the
     * message.
     */
    @EventListener
    public void declined(PullRequestDeclinedEvent event) {
        //log.info("Pull Request Declined Event occurred.");
        try {
            Message message = getMessage(event, ".pullrequest.declined");
            sendMessage(message);
        } catch(Exception e) {
            sendMail("An error occurred while handling a declined pull request event " + e.getMessage());
        }
    }

    /*
     * This event fires when the approval status of a pull request changes. It sends a Fedmsg message to
     * the relevant topic and adds in who is responsible for the change in status.
     */
    @EventListener
    public void approvalStatusChange(PullRequestApprovalEvent event) {
        //log.info("Pull Request Approval Event occurred.");
        try {
            Message message = getMessage(event);
            sendMessage(message);
        } catch(Exception e) {
            sendMail("An error occurred while handling an approvalStatusChange event " + e.getMessage());
        }
    }

    /*
     * This event fires when reviewers for a pull request are modified and sends a Fedmsg message to
     * the relevant topic with a list of the newly added or recently deleted reviewer(s).
     */
    @EventListener
    public void reviewersModified(PullRequestRolesUpdatedEvent event) {
        //log.info("Pull Request ReviewersModified Event occurred.");
        try {
            Message message = getMessage(event, ".pullrequest.reviewersmodified");
            sendMessage(message);
        } catch(Exception e) {
            sendMail("An error occurred while handling a reviewersModified event " + e.getMessage());
        }
    }
}
