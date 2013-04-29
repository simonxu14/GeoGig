package org.geogit.remote;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.annotation.Nullable;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.geogit.api.Bucket;
import org.geogit.api.Node;
import org.geogit.api.NodeRef;
import org.geogit.api.ObjectId;
import org.geogit.api.Ref;
import org.geogit.api.RevCommit;
import org.geogit.api.RevObject;
import org.geogit.api.RevObject.TYPE;
import org.geogit.api.RevTree;
import org.geogit.api.SymRef;
import org.geogit.api.plumbing.DiffTree;
import org.geogit.api.plumbing.FindCommonAncestor;
import org.geogit.api.plumbing.RevObjectParse;
import org.geogit.api.plumbing.diff.DiffEntry;
import org.geogit.api.porcelain.PushException;
import org.geogit.api.porcelain.PushException.StatusCode;
import org.geogit.repository.Repository;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Closeables;

/**
 * An implementation of a remote repository that exists on a remote machine and made public via an
 * http interface.
 * 
 * @see IRemoteRepo
 */
public class HttpRemoteRepo extends AbstractRemoteRepo {

    private URL repositoryURL;

    private List<ObjectId> fetchedIds;

    /**
     * Constructs a new {@code HttpRemoteRepo} with the given parameters.
     * 
     * @param repositoryURL the url of the remote repository
     */
    public HttpRemoteRepo(URL repositoryURL) {
        String url = repositoryURL.toString();
        if (url.endsWith("/")) {
            url = url.substring(0, url.lastIndexOf('/'));
        }
        try {
            this.repositoryURL = new URL(url);
        } catch (MalformedURLException e) {
            this.repositoryURL = repositoryURL;
        }
    }

    /**
     * Currently does nothing for HTTP Remote.
     * 
     * @throws IOException
     */
    @Override
    public void open() throws IOException {

    }

    /**
     * Currently does nothing for HTTP Remote.
     * 
     * @throws IOException
     */
    @Override
    public void close() throws IOException {

    }

    /**
     * @return the remote's HEAD {@link Ref}.
     */
    @Override
    public Ref headRef() {
        HttpURLConnection connection = null;
        Ref headRef = null;
        try {
            String expanded = repositoryURL.toString() + "/repo/manifest";

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            // Get Response
            InputStream is = connection.getInputStream();
            try {
                BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                String line;

                while ((line = rd.readLine()) != null) {
                    if (line.startsWith("HEAD")) {
                        headRef = parseRef(line);
                    }
                }
                rd.close();
            } finally {
                is.close();
            }

        } catch (Exception e) {

            Throwables.propagate(e);

        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
        return headRef;
    }

    /**
     * List the remote's {@link Ref refs}.
     * 
     * @param getHeads whether to return refs in the {@code refs/heads} namespace
     * @param getTags whether to return refs in the {@code refs/tags} namespace
     * @return an immutable set of refs from the remote
     */
    @Override
    public ImmutableSet<Ref> listRefs(final boolean getHeads, final boolean getTags) {
        HttpURLConnection connection = null;
        ImmutableSet.Builder<Ref> builder = new ImmutableSet.Builder<Ref>();
        try {
            String expanded = repositoryURL.toString() + "/repo/manifest";

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            // Get Response
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            String line;
            try {
                while ((line = rd.readLine()) != null) {
                    if ((getHeads && line.startsWith("refs/heads"))
                            || (getTags && line.startsWith("refs/tags"))) {
                        builder.add(parseRef(line));
                    }
                }
            } finally {
                rd.close();
            }

        } catch (Exception e) {
            throw Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
        return builder.build();
    }

    /**
     * @param connection
     */
    private void consumeErrStreamAndCloseConnection(@Nullable HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            InputStream es = ((HttpURLConnection) connection).getErrorStream();
            consumeAndCloseStream(es);
        } catch (IOException ex) {
            throw Throwables.propagate(ex);
        } finally {
            connection.disconnect();
        }
    }

    private void consumeAndCloseStream(InputStream stream) throws IOException {
        if (stream != null) {
            try {
                // read the response body
                while (stream.read() > -1) {
                    ;
                }
            } finally {
                // close the errorstream
                Closeables.closeQuietly(stream);
            }
        }
    }

    private Ref parseRef(String refString) {
        Ref ref = null;
        String[] tokens = refString.split(" ");
        if (tokens.length == 2) {
            // normal ref
            // NAME HASH
            String name = tokens[0];
            ObjectId objectId = ObjectId.valueOf(tokens[1]);
            ref = new Ref(name, objectId, RevObject.TYPE.COMMIT);
        } else {
            // symbolic ref
            // NAME TARGET HASH
            String name = tokens[0];
            String targetRef = tokens[1];
            ObjectId targetObjectId = ObjectId.valueOf(tokens[2]);
            Ref target = new Ref(targetRef, targetObjectId, RevObject.TYPE.COMMIT);
            ref = new SymRef(name, target);

        }
        return ref;
    }

    /**
     * CommitTraverser for pushes from a shallow clone. This works just like a normal push, but will
     * throw an appropriate push exception when the history is not deep enough to perform the push.
     */
    private class ShallowPushTraverser extends CommitTraverser {

        Repository source;

        public ShallowPushTraverser(Repository source) {
            super(source.getGraphDatabase());
            this.source = source;
        }

        @Override
        protected Evaluation evaluate(CommitNode commitNode) {

            if (networkObjectExists(commitNode.getObjectId(), source)) {
                return Evaluation.EXCLUDE_AND_PRUNE;
            }

            if (!commitNode.getObjectId().equals(ObjectId.NULL)
                    && !source.getObjectDatabase().exists(commitNode.getObjectId())) {
                // Source is too shallow
                throw new PushException(StatusCode.HISTORY_TOO_SHALLOW);
            }

            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        @Override
        protected void apply(CommitNode commitNode) {
            walkCommit(commitNode.getObjectId(), source, true);
        }
    }

    /**
     * CommitTraverser for fetches from a shallow clone. This traverser will fetch data up to the
     * fetch limit. If no fetch limit is defined, one will be calculated when a commit is fetched
     * that I already have. The new fetch depth will be the depth from the starting commit to
     * beginning of the orphaned branch.
     */
    private class ShallowFetchTraverser extends CommitTraverser {

        Optional<Integer> fetchLimit;

        Repository destination;

        public ShallowFetchTraverser(Repository destination, Optional<Integer> fetchLimit) {
            super(destination.getGraphDatabase());
            this.destination = destination;
            this.fetchLimit = fetchLimit;
        }

        @Override
        protected Evaluation evaluate(CommitNode commitNode) {
            if (fetchLimit.isPresent() && commitNode.getDepth() > fetchLimit.get()) {
                return Evaluation.EXCLUDE_AND_PRUNE;
            }

            if (!fetchLimit.isPresent()
                    && destination.getObjectDatabase().exists(commitNode.getObjectId())) {
                // calculate the new fetch limit
                fetchLimit = Optional.of(destination.getGraphDatabase().getDepth(
                        commitNode.getObjectId())
                        + commitNode.getDepth() - 1);
            }
            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        @Override
        protected void apply(CommitNode commitNode) {
            walkCommit(commitNode.getObjectId(), destination, false);
        }
    };

    /**
     * CommitTraverser for fetches in a full repository. This traverser will fetch data until the
     * local repository is up to date.
     */
    private class DeepFetchTraverser extends CommitTraverser {

        Repository destination;

        public DeepFetchTraverser(Repository destination) {
            super(destination.getGraphDatabase());
            this.destination = destination;
        }

        @Override
        protected Evaluation evaluate(CommitNode commitNode) {
            if (destination.getObjectDatabase().exists(commitNode.getObjectId())) {
                return Evaluation.EXCLUDE_AND_PRUNE;
            }
            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        @Override
        protected void apply(CommitNode commitNode) {
            walkCommit(commitNode.getObjectId(), destination, false);
        }
    };

    /**
     * CommitTraverser for pushes from a full repository. This traverser will push all commits that
     * are not already on the remote repository.
     */
    private class DeepPushTraverser extends CommitTraverser {

        Repository source;

        public DeepPushTraverser(Repository source) {
            super(source.getGraphDatabase());
            this.source = source;
        }

        @Override
        protected Evaluation evaluate(CommitNode commitNode) {
            if (networkObjectExists(commitNode.getObjectId(), source)) {
                return Evaluation.EXCLUDE_AND_PRUNE;
            }
            return Evaluation.INCLUDE_AND_CONTINUE;
        }

        @Override
        protected void apply(CommitNode commitNode) {
            walkCommit(commitNode.getObjectId(), source, true);
        }
    };

    /**
     * Fetch all new objects from the specified {@link Ref} from the remote.
     * 
     * @param localRepository the repository to add new objects to
     * @param ref the remote ref that points to new commit data
     * @param fetchLimit the maximum depth to fetch
     */
    @Override
    public void fetchNewData(Repository localRepository, Ref ref, Optional<Integer> fetchLimit) {
        fetchedIds = new LinkedList<ObjectId>();

        CommitTraverser traverser;
        if (localRepository.getDepth().isPresent()) {
            traverser = new ShallowFetchTraverser(localRepository, fetchLimit);
        } else {
            traverser = new DeepFetchTraverser(localRepository);
        }

        try {
            traverser.traverse(ref.getObjectId());
        } catch (Exception e) {
            for (ObjectId oid : fetchedIds) {
                localRepository.getObjectDatabase().delete(oid);
            }
            Throwables.propagate(e);
        } finally {
            fetchedIds.clear();
            fetchedIds = null;
        }
    }

    /**
     * Push all new objects from the specified {@link Ref} to the remote.
     * 
     * @param localRepository the repository to get new objects from
     * @param ref the local ref that points to new commit data
     */
    @Override
    public void pushNewData(Repository localRepository, Ref ref) throws PushException {
        pushNewData(localRepository, ref, ref.getName());
    }

    /**
     * Push all new objects from the specified {@link Ref} to the remote.
     * 
     * @param localRepository the repository to get new objects from
     * @param ref the local ref that points to new commit data
     * @param refspec the remote branch to push to
     */
    @Override
    public void pushNewData(Repository localRepository, Ref ref, String refspec)
            throws PushException {
        Optional<Ref> remoteRef = checkPush(localRepository, ref, refspec);
        beginPush();

        CommitTraverser traverser;
        if (localRepository.getDepth().isPresent()) {
            traverser = new ShallowPushTraverser(localRepository);
        } else {
            traverser = new DeepPushTraverser(localRepository);
        }

        traverser.traverse(ref.getObjectId());

        ObjectId originalRemoteRefValue = ObjectId.NULL;
        if (remoteRef.isPresent()) {
            originalRemoteRefValue = remoteRef.get().getObjectId();
        }
        endPush(refspec, ref.getObjectId().toString(), originalRemoteRefValue.toString());
    }

    private Optional<Ref> checkPush(Repository localRepository, Ref ref, String refspec)
            throws PushException {
        Optional<Ref> remoteRef = getRemoteRef(refspec);
        if (remoteRef.isPresent()) {
            if (remoteRef.get().getObjectId().equals(ref.getObjectId())) {
                // The branches are equal, no need to push.
                throw new PushException(StatusCode.NOTHING_TO_PUSH);
            } else if (localRepository.blobExists(remoteRef.get().getObjectId())) {
                RevCommit leftCommit = localRepository.getCommit(remoteRef.get().getObjectId());
                RevCommit rightCommit = localRepository.getCommit(ref.getObjectId());
                Optional<RevCommit> ancestor = localRepository.command(FindCommonAncestor.class)
                        .setLeft(leftCommit).setRight(rightCommit).call();
                if (!ancestor.isPresent()) {
                    // There is no common ancestor, a push will overwrite history
                    throw new PushException(StatusCode.REMOTE_HAS_CHANGES);
                } else if (ancestor.get().getId().equals(ref.getObjectId())) {
                    // My last commit is the common ancestor, the remote already has my data.
                    throw new PushException(StatusCode.NOTHING_TO_PUSH);
                } else if (!ancestor.get().getId().equals(remoteRef.get().getObjectId())) {
                    // The remote branch's latest commit is not my ancestor, a push will cause a
                    // loss of history.
                    throw new PushException(StatusCode.REMOTE_HAS_CHANGES);
                }
            } else {
                // The remote has data that I do not, a push will cause this data to be lost.
                throw new PushException(StatusCode.REMOTE_HAS_CHANGES);
            }
        }
        return remoteRef;
    }

    /**
     * Delete a {@link Ref} from the remote repository.
     * 
     * @param refspec the ref to delete
     */
    @Override
    public void deleteRef(String refspec) {
        updateRemoteRef(refspec, null, true);
    }

    private void beginPush() {
        HttpURLConnection connection = null;
        try {
            String internalIp = InetAddress.getLocalHost().getHostName();
            String expanded = repositoryURL.toString() + "/repo/beginpush?internalIp=" + internalIp;

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            InputStream stream = connection.getInputStream();
            consumeAndCloseStream(stream);

        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
    }

    private void endPush(String refspec, String oid, String originalRefValue) {
        HttpURLConnection connection = null;
        try {
            String internalIp = InetAddress.getLocalHost().getHostName();
            String expanded = repositoryURL.toString() + "/repo/endpush?refspec=" + refspec
                    + "&objectId=" + oid + "&internalIp=" + internalIp + "&originalRefValue="
                    + originalRefValue;

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            connection.getInputStream();

        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
    }

    private Optional<Ref> getRemoteRef(String refspec) {
        HttpURLConnection connection = null;
        Optional<Ref> remoteRef = Optional.absent();
        try {
            String expanded = repositoryURL.toString() + "/refparse?name=" + refspec;

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            InputStream inputStream = connection.getInputStream();

            XMLStreamReader reader = XMLInputFactory.newFactory()
                    .createXMLStreamReader(inputStream);

            try {
                readToElementStart(reader, "Ref");
                if (reader.hasNext()) {

                    readToElementStart(reader, "name");
                    final String refName = reader.getElementText();

                    readToElementStart(reader, "objectId");
                    final String objectId = reader.getElementText();

                    readToElementStart(reader, "target");
                    String target = null;
                    if (reader.hasNext()) {
                        target = reader.getElementText();
                    }
                    reader.close();

                    if (target != null) {
                        remoteRef = Optional.of((Ref) new SymRef(refName, new Ref(target, ObjectId
                                .valueOf(objectId), RevObject.TYPE.COMMIT)));
                    } else {
                        remoteRef = Optional.of(new Ref(refName, ObjectId.valueOf(objectId),
                                RevObject.TYPE.COMMIT));
                    }
                }

            } finally {
                reader.close();
                inputStream.close();
            }
        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
        return remoteRef;
    }

    private Ref updateRemoteRef(String refspec, ObjectId newValue, boolean delete) {
        HttpURLConnection connection = null;
        Ref updatedRef = null;
        try {
            String expanded;
            if (!delete) {
                expanded = repositoryURL.toString() + "/updateref?name=" + refspec + "&newValue="
                        + newValue.toString();
            } else {
                expanded = repositoryURL.toString() + "/updateref?name=" + refspec + "&delete=true";
            }

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            InputStream inputStream = connection.getInputStream();

            XMLStreamReader reader = XMLInputFactory.newFactory()
                    .createXMLStreamReader(inputStream);

            try {
                readToElementStart(reader, "ChangedRef");

                readToElementStart(reader, "name");
                final String refName = reader.getElementText();

                readToElementStart(reader, "objectId");
                final String objectId = reader.getElementText();

                readToElementStart(reader, "target");
                String target = null;
                if (reader.hasNext()) {
                    target = reader.getElementText();
                }
                reader.close();

                if (target != null) {
                    updatedRef = new SymRef(refName, new Ref(target, ObjectId.valueOf(objectId),
                            RevObject.TYPE.COMMIT));
                } else {
                    updatedRef = new Ref(refName, ObjectId.valueOf(objectId), RevObject.TYPE.COMMIT);
                }

            } finally {
                reader.close();
                inputStream.close();
            }
        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
        return updatedRef;
    }

    private void readToElementStart(XMLStreamReader reader, String name) throws XMLStreamException {
        while (reader.hasNext()) {
            if (reader.isStartElement() && reader.getLocalName().equals(name)) {
                break;
            }
            reader.next();
        }
    }

    private void walkCommit(ObjectId commitId, Repository localRepo, boolean sendObject) {
        Optional<RevObject> object = sendObject ? sendNetworkObject(commitId, localRepo)
                : getNetworkObject(commitId, localRepo);
        if (object.isPresent() && object.get().getType().equals(TYPE.COMMIT)) {
            RevCommit commit = (RevCommit) object.get();
            walkTree(commit.getTreeId(), localRepo, sendObject);

            if (sendObject) {
                // Send the features that changed.
                ObjectId parentId = commit.getParentIds().get(0);
                RevCommit parentCommit = localRepo.getCommit(parentId);
                Iterator<DiffEntry> diff = localRepo.command(DiffTree.class)
                        .setOldTree(parentCommit.getId()).setNewTree(commit.getId()).call();

                while (diff.hasNext()) {
                    DiffEntry entry = diff.next();
                    if (entry.getNewObject() != null) {
                        NodeRef nodeRef = entry.getNewObject();
                        moveObject(nodeRef.getNode().getObjectId(), localRepo, true);
                        ObjectId metadataId = nodeRef.getMetadataId();
                        if (!metadataId.isNull()) {
                            moveObject(metadataId, localRepo, true);
                        }
                    }
                }
            }
        }
    }

    private void walkTree(ObjectId treeId, Repository localRepo, boolean sendObject) {
        // See if we already have it
        if (sendObject) {
            if (networkObjectExists(treeId, localRepo)) {
                return;
            }
        } else if (localRepo.getObjectDatabase().exists(treeId)) {
            return;
        }

        Optional<RevObject> object = sendObject ? sendNetworkObject(treeId, localRepo)
                : getNetworkObject(treeId, localRepo);
        if (object.isPresent() && object.get().getType().equals(TYPE.TREE)) {
            RevTree tree = (RevTree) object.get();

            walkLocalTree(tree, localRepo, sendObject);
        }
    }

    private void walkLocalTree(RevTree tree, Repository localRepo, boolean sendObject) {
        // walk subtrees
        if (tree.buckets().isPresent()) {
            for (Bucket bucket : tree.buckets().get().values()) {
                ObjectId bucketId = bucket.id();
                walkTree(bucketId, localRepo, sendObject);
            }
        } else {
            // get new objects
            for (Iterator<Node> children = tree.children(); children.hasNext();) {
                Node ref = children.next();
                if (ref.getType() == RevObject.TYPE.TREE || !sendObject) {
                    moveObject(ref.getObjectId(), localRepo, sendObject);
                    ObjectId metadataId = ref.getMetadataId().or(ObjectId.NULL);
                    if (!metadataId.isNull()) {
                        moveObject(metadataId, localRepo, sendObject);
                    }
                }
            }
        }
    }

    private void moveObject(ObjectId objectId, Repository localRepo, boolean sendObject) {
        // See if we already have it
        if (sendObject) {
            if (networkObjectExists(objectId, localRepo)) {
                return;
            }
        } else if (localRepo.getObjectDatabase().exists(objectId)) {
            return;
        }

        Optional<RevObject> childObject = sendObject ? sendNetworkObject(objectId, localRepo)
                : getNetworkObject(objectId, localRepo);
        if (childObject.isPresent()) {
            RevObject revObject = childObject.get();
            if (TYPE.TREE.equals(revObject.getType())) {
                walkLocalTree((RevTree) revObject, localRepo, sendObject);
            }
        }
    }

    private boolean networkObjectExists(ObjectId objectId, Repository localRepo) {
        HttpURLConnection connection = null;
        boolean exists = false;
        try {
            String internalIp = InetAddress.getLocalHost().getHostName();
            String expanded = repositoryURL.toString() + "/repo/exists?oid=" + objectId.toString()
                    + "&internalIp=" + internalIp;

            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            // Get Response
            InputStream is = connection.getInputStream();
            try {
                BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                String line = rd.readLine();
                Preconditions.checkNotNull(line, "networkObjectExists returned no dat for %s",
                        expanded);
                exists = line.startsWith("1");
            } finally {
                consumeAndCloseStream(is);
            }

        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
        return exists;
    }

    private Optional<RevObject> getNetworkObject(ObjectId objectId, Repository localRepo) {
        HttpURLConnection connection = null;
        try {
            String expanded = repositoryURL.toString() + "/repo/objects/" + objectId.toString();
            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("GET");

            connection.setUseCaches(false);
            connection.setDoOutput(true);

            // Get Response
            InputStream is = connection.getInputStream();
            try {
                localRepo.getObjectDatabase().put(objectId, is);
            } finally {
                consumeAndCloseStream(is);
            }
        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }

        return localRepo.command(RevObjectParse.class).setObjectId(objectId).call();

    }

    private Optional<RevObject> sendNetworkObject(ObjectId objectId, Repository localRepo) {
        Optional<RevObject> object = localRepo.command(RevObjectParse.class).setObjectId(objectId)
                .call();

        HttpURLConnection connection = null;
        try {
            String internalIp = InetAddress.getLocalHost().getHostName();
            String expanded = repositoryURL.toString() + "/repo/sendobject?internalIp="
                    + internalIp;
            connection = (HttpURLConnection) new URL(expanded).openConnection();
            connection.setRequestMethod("POST");

            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setDoOutput(true);

            DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
            try {
                wr.write(objectId.getRawValue());
                InputStream rawObject = localRepo.getIndex().getDatabase().getRaw(objectId);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = rawObject.read(buffer)) != -1) {
                    wr.write(buffer, 0, bytesRead);
                }
                wr.flush();
            } finally {
                wr.close();
            }

            // Get Response
            InputStream is = connection.getInputStream();
            try {
                BufferedReader rd = new BufferedReader(new InputStreamReader(is));
                String line;

                while ((line = rd.readLine()) != null) {
                    if (line.contains("Object already existed")) {
                        return Optional.absent();
                    }
                }
                rd.close();
            } finally {
                consumeAndCloseStream(is);
            }
        } catch (Exception e) {
            Throwables.propagate(e);
        } finally {
            consumeErrStreamAndCloseConnection(connection);
        }
        return object;
    }
}
