Stash Fedmsg Plugin
===================

This is the repository for the plugin that takes data from events in Stash (commits, tag/branch creation,
pull requests) and prints data regarding them to a fedmsg bus for anyone to use. 

The entry level for the code is any method marked with an "@EventListener" tag.

For example: public void refChangeListener(RepositoryRefsChangedEvent event)
This method is executed whenever any kind of ref is changed in Stash. Examples of this event could be something creating/deleting a tag, creating/deleting a branch, merging a branch, or just simply commiting to Stash.  
