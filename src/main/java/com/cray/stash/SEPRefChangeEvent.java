package com.cray.stash;

import com.atlassian.stash.event.RepositoryRefsChangedEvent;
import com.atlassian.stash.repository.*;
import com.atlassian.stash.util.Page;
import com.atlassian.stash.util.PageUtils;

import java.util.Collection;
import java.util.HashSet;

/**
 * Created by swalter on 5/18/2016.
 */
public abstract class SEPRefChangeEvent {
    RefService repoData;

    public SEPRefChangeEvent(RefService repoData) {
        this.repoData = repoData;
     }

    public void sort () {

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
            //sendmail("An error occurred while finding all the latest refIds for each branch in a repo " + e.getMessage());
        }
        //log.info("Found the latest refs: " + refIds);
        return refIds;
    }
}
