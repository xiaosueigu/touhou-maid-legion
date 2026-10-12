package com.dsh.maidmanager;

import com.dsh.maidmanager.logic.MaidRegistry;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the bookkeeping that decides whether a stored chunk ticket is still wanted.
 *
 * <p>That decision is what stops an orphaned ticket from keeping a chunk entity-ticking forever:
 * the tickets live in the level's saved data and are reinstated on the next start, so a wrong
 * "still wanted" answer outlives the restart that would otherwise have cleaned it up.
 */
public class MaidRegistryForceLoadTest {

    @Test
    public void switchIsTrackedPerOwner() {
        MaidRegistry registry = new MaidRegistry();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID maid = UUID.randomUUID();

        assertFalse("nothing is force loaded yet", registry.isForceLoad(owner, maid));
        registry.setForceLoad(owner, maid, true);

        assertTrue("the owner that flipped the switch sees it on", registry.isForceLoad(owner, maid));
        assertFalse("another owner does not", registry.isForceLoad(other, maid));
    }

    @Test
    public void oneOwnerIsEnoughToKeepTheTicketAlive() {
        MaidRegistry registry = new MaidRegistry();
        UUID owner = UUID.randomUUID();
        UUID maid = UUID.randomUUID();

        registry.setForceLoad(owner, maid, true);
        assertTrue(registry.isForceLoadByAnyone(maid));

        registry.setForceLoad(owner, maid, false);
        assertFalse("with every switch off the ticket is stale",
                registry.isForceLoadByAnyone(maid));
    }

    @Test
    public void onlyAnEnabledSwitchCountsAsUse() {
        MaidRegistry registry = new MaidRegistry();
        UUID owner = UUID.randomUUID();
        UUID maid = UUID.randomUUID();

        assertFalse("a fresh registry uses no force-load", registry.hasAnyForceLoad());

        // Turning a switch off for a maid that was never on still creates the owner's entry.
        registry.setForceLoad(owner, maid, false);
        assertFalse("an empty owner entry is not a use", registry.hasAnyForceLoad());

        registry.setForceLoad(owner, maid, true);
        assertTrue(registry.hasAnyForceLoad());

        registry.setForceLoad(owner, maid, false);
        assertFalse("turning the last switch off ends the use", registry.hasAnyForceLoad());
    }
}
