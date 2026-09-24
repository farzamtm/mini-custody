package com.farzam.custody.approval;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The registry of people whose signatures count. */
public interface ApproverRepository extends JpaRepository<Approver, UUID> {

    /**
     * The approvers behind a set of approvals, in one query.
     *
     * <p>Used when the quorum is complete and the event has to carry each approver's public key
     * alongside their signature. Fetching them one at a time would be an N+1 on the one code path in
     * this service where N is under an attacker's influence — an event can carry as many approvals as
     * there are approvers.
     *
     * @param ids the approver ids from the collected approvals
     * @return the matching approvers, in no particular order
     */
    List<Approver> findByIdIn(Collection<UUID> ids);
}
