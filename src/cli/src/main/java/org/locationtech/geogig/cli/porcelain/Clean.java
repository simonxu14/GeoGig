/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.locationtech.geogig.cli.porcelain;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import jline.console.ConsoleReader;

import org.locationtech.geogig.api.GeoGIG;
import org.locationtech.geogig.api.NodeRef;
import org.locationtech.geogig.api.RevObject.TYPE;
import org.locationtech.geogig.api.plumbing.DiffWorkTree;
import org.locationtech.geogig.api.plumbing.FindTreeChild;
import org.locationtech.geogig.api.plumbing.diff.DiffEntry;
import org.locationtech.geogig.api.plumbing.diff.DiffEntry.ChangeType;
import org.locationtech.geogig.api.porcelain.CleanOp;
import org.locationtech.geogig.cli.AbstractCommand;
import org.locationtech.geogig.cli.GeogigCLI;
import org.locationtech.geogig.cli.annotation.ObjectDatabaseReadOnly;
import org.locationtech.geogig.repository.Repository;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Optional;

@ObjectDatabaseReadOnly
@Parameters(commandNames = "clean", commandDescription = "Deletes untracked features from working tree")
public class Clean extends AbstractCommand {

    @Parameter(description = "<path>")
    private List<String> path = new ArrayList<String>();

    @Parameter(names = { "--dry-run", "-n" }, description = "Don't actually remove anything, just show what would be done.")
    private boolean dryRun;

    @Override
    public void runInternal(GeogigCLI cli) throws IOException {
        final ConsoleReader console = cli.getConsole();
        final GeoGIG geogit = cli.getGeogit();

        String pathFilter = null;
        if (!path.isEmpty()) {
            pathFilter = path.get(0);
        }

        if (dryRun) {
            if (pathFilter != null) {
                // check that is a valid path
                Repository repository = cli.getGeogit().getRepository();
                NodeRef.checkValidPath(pathFilter);

                Optional<NodeRef> ref = repository.command(FindTreeChild.class).setIndex(true)
                        .setParent(repository.workingTree().getTree()).setChildPath(pathFilter)
                        .call();

                checkParameter(ref.isPresent(), "pathspec '%s' did not match any tree", pathFilter);
                checkParameter(ref.get().getType() == TYPE.TREE,
                        "pathspec '%s' did not resolve to a tree", pathFilter);
            }
            Iterator<DiffEntry> unstaged = geogit.command(DiffWorkTree.class).setFilter(pathFilter)
                    .call();
            while (unstaged.hasNext()) {
                DiffEntry entry = unstaged.next();
                if (entry.changeType() == ChangeType.ADDED) {
                    console.println("Would remove " + entry.newPath());
                }
            }
        } else {
            geogit.command(CleanOp.class).setPath(pathFilter).call();
            console.println("Clean operation completed succesfully.");
        }
    }

}
