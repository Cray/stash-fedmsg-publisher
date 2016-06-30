Stash Fedmsg Plugin
===================

This is the repository for the plugin that takes data from events in Stash (commits, tag/branch creation,
pull requests) and prints data regarding them to a fedmsg bus for anyone to use. 

The entry level for the code is any method marked with an "@EventListener" tag.
