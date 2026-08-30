package com.offcanon.application;

import com.offcanon.infrastructure.memory.InMemoryExperimentRepository;
import com.offcanon.infrastructure.memory.InMemoryPromotionLock;
import com.offcanon.infrastructure.memory.InMemoryProjectRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRepository;
import com.offcanon.infrastructure.memory.InMemorySessionRunLease;
import com.offcanon.infrastructure.memory.InMemorySnapshotRepository;
import com.offcanon.project.domain.Project;
import com.offcanon.shared.web.ForbiddenException;
import com.offcanon.port.ClockPort;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.Mockito.mock;

class OwnershipServiceTest {
    @Test
    void projectListingAndReadsAreScopedToTheAuthenticatedOwner() {
        InMemoryProjectRepository repository = new InMemoryProjectRepository();
        ProjectApplicationService service = new ProjectApplicationService(repository, new NoopSnapshotPort());
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Project project = service.register(owner, "demo", Path.of("D:/projects/demo").toString(), List.of("mvn test"));

        assertEquals(List.of(project), service.list(owner));
        assertEquals(List.of(), service.list(other));
        assertThrows(ForbiddenException.class, () -> service.get(project.id(), other));
    }

    @Test
    void sessionAndExperimentOperationsRejectAProjectOwnedByAnotherUser() {
        InMemoryProjectRepository projects = new InMemoryProjectRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryExperimentRepository experiments = new InMemoryExperimentRepository();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Project project = Project.create(owner, "demo", Path.of("D:/projects/demo"), List.of("mvn test"), Instant.now());
        projects.save(project);
        ExperimentApplicationService service = new ExperimentApplicationService(projects, sessions, experiments,
                new InMemorySnapshotRepository(), new NoopSnapshotPort(), mock(com.offcanon.port.WorkspacePort.class),
                Instant::now, new InMemorySessionRunLease(), new InMemoryPromotionLock());

        service.createSession(owner, project.id(), "owner session");
        assertEquals(1, service.listSessions(project.id(), owner).size());
        assertThrows(ForbiddenException.class, () -> service.listSessions(project.id(), other));
        assertThrows(ForbiddenException.class, () -> service.createSession(other, project.id(), "intruder"));
        assertThrows(ForbiddenException.class, () -> service.create(other, project.id(), null, null, "intruder task"));
    }

    private static final class NoopSnapshotPort implements com.offcanon.port.SnapshotPort {
        @Override
        public com.offcanon.workspace.domain.Snapshot capture(Project project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.offcanon.workspace.domain.Snapshot captureWorkspace(Project project, Path workspace, String parentFingerprint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String currentFingerprint(Project project) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String fingerprintWorkspace(Project project, Path workspace, String parentFingerprint) {
            throw new UnsupportedOperationException();
        }
    }
}
