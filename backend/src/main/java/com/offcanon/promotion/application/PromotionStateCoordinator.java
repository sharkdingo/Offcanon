package com.offcanon.promotion.application;

import com.offcanon.experiment.domain.Experiment;
import com.offcanon.experiment.domain.ExperimentStatus;
import com.offcanon.port.ExperimentRepository;
import com.offcanon.port.PromotionJournalPort;
import com.offcanon.promotion.domain.PromotionJournal;
import com.offcanon.shared.domain.DomainException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.Supplier;

/** Keeps paired lifecycle/journal CAS updates in one short database transaction. */
@Component
public class PromotionStateCoordinator {
    private final ExperimentRepository experiments;
    private final PromotionJournalPort journals;
    private final PlatformTransactionManager transactionManager;

    @Autowired
    public PromotionStateCoordinator(ExperimentRepository experiments,
                                     PromotionJournalPort journals,
                                     ObjectProvider<PlatformTransactionManager> transactionManagers) {
        this(experiments, journals, transactionManagers.getIfAvailable());
    }

    public PromotionStateCoordinator(ExperimentRepository experiments,
                                     PromotionJournalPort journals,
                                     PlatformTransactionManager transactionManager) {
        this.experiments = experiments;
        this.journals = journals;
        this.transactionManager = transactionManager;
    }

    public PromotionJournal beginApplying(Experiment experiment, PromotionJournal prepared, Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            PromotionJournal durable = currentJournal(prepared);
            ensureJournalBelongsToExperiment(durable, current);

            // A worker can be interrupted between either side of the paired
            // lifecycle/journal writes. Complete the already-started phase
            // instead of trying to apply a second transition from a stale
            // detached object.
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.APPLYING) {
                if (current.status() == ExperimentStatus.PROMOTING) return durable;
                if (current.status() == ExperimentStatus.VERIFIED) {
                    current.beginPromotion();
                    experiments.save(current);
                }
                if (current.status() == ExperimentStatus.PREPARING_PROMOTION) {
                    current.markPromoting();
                    experiments.save(current);
                    return durable;
                }
                if (current.status() == ExperimentStatus.PROMOTING) return durable;
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot resume applying promotion from " + current.status());
            }
            if (durable.phase() != com.offcanon.promotion.domain.PromotionPhase.PREPARED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot begin applying from journal phase " + durable.phase());
            }

            if (current.status() == ExperimentStatus.VERIFIED) {
                current.beginPromotion();
                experiments.save(current);
            } else if (current.status() != ExperimentStatus.PREPARING_PROMOTION
                    && current.status() != ExperimentStatus.PROMOTING) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot begin promotion from " + current.status());
            }
            PromotionJournal applying = journals.markApplying(durable, now);
            current = currentExperiment(experiment);
            if (current.status() == ExperimentStatus.PREPARING_PROMOTION) {
                current.markPromoting();
                experiments.save(current);
            } else if (current.status() != ExperimentStatus.PROMOTING) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot mark promotion as running from " + current.status());
            }
            return applying;
        });
    }

    public PromotionJournal commit(Experiment experiment,
                                   PromotionJournal applying,
                                   String fingerprint,
                                   Instant now) {
        return inTransaction(() -> {
            Experiment current = experiments.findById(experiment.id())
                    .orElseThrow(() -> new DomainException("EXPERIMENT_MISSING", "Promotion experiment disappeared"));
            PromotionJournal durable = currentJournal(applying);
            ensureJournalBelongsToExperiment(durable, current);

            // The filesystem may have been committed just before a process or
            // lease failure. A retry must treat a durable terminal journal as
            // authoritative and only repair the lifecycle marker.
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED) {
                reconcileCommittedExperiment(current);
                return durable;
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot commit an aborted promotion journal");
            }
            if (durable.phase() != com.offcanon.promotion.domain.PromotionPhase.APPLYING
                    && durable.phase() != com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot commit promotion from journal phase " + durable.phase());
            }

            if (current.status() != ExperimentStatus.PROMOTED) {
                reconcileCommittedExperiment(current);
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                return journals.resolveRecoveryCommitted(durable, fingerprint, now);
            }
            return journals.markCommitted(durable, fingerprint, now);
        });
    }

    public PromotionJournal requireRecovery(Experiment experiment,
                                             PromotionJournal journal,
                                             String reason,
                                             Instant now) {
        return inTransaction(() -> {
            Experiment current = experiments.findById(experiment.id())
                    .orElseThrow(() -> new DomainException("EXPERIMENT_MISSING", "Promotion experiment disappeared"));
            PromotionJournal durable = currentJournal(journal);
            ensureJournalBelongsToExperiment(durable, current);
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED) {
                reconcileCommittedExperiment(current);
                return durable;
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED) {
                reconcileAbortedExperiment(current);
                return durable;
            }
            if (current.status() != ExperimentStatus.RECOVERY_REQUIRED) {
                current.requirePromotionRecovery(reason);
                experiments.save(current);
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                return durable;
            }
            return journals.markRecoveryRequired(durable, reason, now);
        });
    }

    public PromotionJournal abortToBase(Experiment experiment,
                                        PromotionJournal applying,
                                        String reason,
                                        Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            PromotionJournal durable = currentJournal(applying);
            ensureJournalBelongsToExperiment(durable, current);
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot abort a committed promotion journal");
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED) {
                reconcileAbortedExperiment(current);
                return durable;
            }
            if (durable.phase() != com.offcanon.promotion.domain.PromotionPhase.PREPARED
                    && durable.phase() != com.offcanon.promotion.domain.PromotionPhase.APPLYING
                    && durable.phase() != com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot abort promotion from journal phase " + durable.phase());
            }
            if (current.status() == ExperimentStatus.PROMOTING
                    || current.status() == ExperimentStatus.RECOVERY_REQUIRED) {
                current.recoverPromotion();
                experiments.save(current);
            } else if (current.status() == ExperimentStatus.PREPARING_PROMOTION) {
                current.abortPromotion(reason);
                experiments.save(current);
            } else if (current.status() != ExperimentStatus.VERIFIED
                    && current.status() != ExperimentStatus.STALE) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot abort promotion from " + current.status());
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                return journals.resolveRecoveryAborted(durable, reason, now);
            }
            return journals.markAborted(durable, reason, now);
        });
    }

    public PromotionJournal stalePrepared(Experiment experiment,
                                          PromotionJournal prepared,
                                          String reason,
                                          Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            PromotionJournal durable = currentJournal(prepared);
            ensureJournalBelongsToExperiment(durable, current);
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED) return durable;
            if (durable.phase() != com.offcanon.promotion.domain.PromotionPhase.PREPARED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot stale a promotion journal in phase " + durable.phase());
            }
            current.markStale(reason);
            experiments.save(current);
            return journals.markAborted(durable, reason, now);
        });
    }

    public PromotionJournal reconcileCommitted(Experiment experiment,
                                                PromotionJournal journal,
                                                String fingerprint,
                                                Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            PromotionJournal durable = currentJournal(journal);
            ensureJournalBelongsToExperiment(durable, current);
            if (durable.phase() != com.offcanon.promotion.domain.PromotionPhase.COMMITTED
                    && durable.phase() != com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot reconcile journal in phase " + durable.phase());
            }
            long before = current.version();
            current.reconcilePromotionCommitted();
            if (current.version() != before) {
                experiments.save(current);
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.COMMITTED) return durable;
            return journals.resolveRecoveryCommitted(durable, fingerprint, now);
        });
    }

    public PromotionJournal reconcileAborted(Experiment experiment,
                                              PromotionJournal journal,
                                              String reason,
                                              Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            PromotionJournal durable = currentJournal(journal);
            ensureJournalBelongsToExperiment(durable, current);
            if (durable.phase() != com.offcanon.promotion.domain.PromotionPhase.ABORTED
                    && durable.phase() != com.offcanon.promotion.domain.PromotionPhase.RECOVERY_REQUIRED) {
                throw new DomainException("PROMOTION_STATE_MISMATCH",
                        "Cannot reconcile journal in phase " + durable.phase());
            }
            long before = current.version();
            current.reconcilePromotionAborted();
            if (current.version() != before) {
                experiments.save(current);
            }
            if (durable.phase() == com.offcanon.promotion.domain.PromotionPhase.ABORTED) return durable;
            return journals.resolveRecoveryAborted(durable, reason, now);
        });
    }

    private PromotionJournal currentJournal(PromotionJournal expected) {
        if (expected == null) {
            throw new DomainException("PROMOTION_JOURNAL_MISSING", "Promotion journal is required");
        }
        return journals.findById(expected.promotionId())
                .orElseThrow(() -> new DomainException("PROMOTION_JOURNAL_MISSING",
                        "Promotion journal disappeared: " + expected.promotionId()));
    }

    private void ensureJournalBelongsToExperiment(PromotionJournal journal, Experiment experiment) {
        if (!journal.experimentId().equals(experiment.id())
                || !journal.projectId().equals(experiment.projectId())) {
            throw new DomainException("PROMOTION_STATE_MISMATCH",
                    "Promotion journal does not belong to experiment " + experiment.id());
        }
    }

    private void reconcileCommittedExperiment(Experiment current) {
        long before = current.version();
        current.reconcilePromotionCommitted();
        if (current.version() != before) experiments.save(current);
    }

    private void reconcileAbortedExperiment(Experiment current) {
        long before = current.version();
        current.reconcilePromotionAborted();
        if (current.version() != before) experiments.save(current);
    }

    private Experiment currentExperiment(Experiment experiment) {
        return experiments.findById(experiment.id())
                .orElseThrow(() -> new DomainException("EXPERIMENT_MISSING", "Promotion experiment disappeared"));
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (transactionManager == null) return work.get();
        T result = new TransactionTemplate(transactionManager).execute(status -> work.get());
        if (result == null) throw new IllegalStateException("Promotion state transaction returned no result");
        return result;
    }
}
