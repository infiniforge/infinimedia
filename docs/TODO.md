# Completed ownership changes

- [x] InfiniMedia owns playlist generation, ordering and persistence. Folder sends a directory identity and grant; Media recursively enumerates it through libinfiniselect, filters supported audio/video/image items, and sorts the queue alphabetically. Authoritative identities remain separate from temporary content URIs.
