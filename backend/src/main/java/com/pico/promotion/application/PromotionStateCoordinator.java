package com.pico.promotion.application;

import com.pico.experiment.domain.Experiment;
import com.pico.experiment.domain.ExperimentStatus;
import com.pico.port.ExperimentRepository;
import com.pico.port.PromotionJournalPort;
import com.pico.promotion.domain.PromotionJournal;
import com.pico.shared.domain.DomainException;
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
            experiment.beginPromotion();
            experiments.save(experiment);
            PromotionJournal applying = journals.markApplying(prepared, now);
            experiment.markPromoting();
            experiments.save(experiment);
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
            if (current.status() != ExperimentStatus.PROMOTED) {
                if (current.status() == ExperimentStatus.PREPARING_PROMOTION) {
                    current.markPromoting();
                    experiments.save(current);
                } else if (current.status() != ExperimentStatus.PROMOTING
                        && current.status() != ExperimentStatus.RECOVERY_REQUIRED) {
                    throw new DomainException("PROMOTION_STATE_MISMATCH",
                            "Cannot commit promotion from " + current.status());
                }
                current.recoverPromotionCommitted();
                experiments.save(current);
            }
            return journals.markCommitted(applying, fingerprint, now);
        });
    }

    public PromotionJournal requireRecovery(Experiment experiment,
                                             PromotionJournal journal,
                                             String reason,
                                             Instant now) {
        return inTransaction(() -> {
            Experiment current = experiments.findById(experiment.id())
                    .orElseThrow(() -> new DomainException("EXPERIMENT_MISSING", "Promotion experiment disappeared"));
            if (current.status() != ExperimentStatus.RECOVERY_REQUIRED) {
                current.requirePromotionRecovery(reason);
                experiments.save(current);
            }
            return journals.markRecoveryRequired(journal, reason, now);
        });
    }

    public PromotionJournal abortToBase(Experiment experiment,
                                        PromotionJournal applying,
                                        String reason,
                                        Instant now) {
        return inTransaction(() -> {
            Experiment current = experiments.findById(experiment.id())
                    .orElseThrow(() -> new DomainException("EXPERIMENT_MISSING", "Promotion experiment disappeared"));
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
            return journals.markAborted(applying, reason, now);
        });
    }

    public PromotionJournal stalePrepared(Experiment experiment,
                                          PromotionJournal prepared,
                                          String reason,
                                          Instant now) {
        return inTransaction(() -> {
            experiment.markStale(reason);
            experiments.save(experiment);
            return journals.markAborted(prepared, reason, now);
        });
    }

    public PromotionJournal reconcileCommitted(Experiment experiment,
                                                PromotionJournal journal,
                                                String fingerprint,
                                                Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            long before = current.version();
            current.reconcilePromotionCommitted();
            if (current.version() != before) {
                experiments.save(current);
            }
            return journals.resolveRecoveryCommitted(journal, fingerprint, now);
        });
    }

    public PromotionJournal reconcileAborted(Experiment experiment,
                                              PromotionJournal journal,
                                              String reason,
                                              Instant now) {
        return inTransaction(() -> {
            Experiment current = currentExperiment(experiment);
            long before = current.version();
            current.reconcilePromotionAborted();
            if (current.version() != before) {
                experiments.save(current);
            }
            return journals.resolveRecoveryAborted(journal, reason, now);
        });
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
