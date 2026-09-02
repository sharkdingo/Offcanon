package com.offcanon.web;

import com.offcanon.application.ProjectCreationApplicationService;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.shared.web.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static com.offcanon.web.ApiDtos.CreateProjectRequest;
import static com.offcanon.web.ApiDtos.ProjectRegistrationResponse;

/** Local-only endpoint for creating a new directory-backed Git project. */
@RestController
@RequestMapping("/api/projects")
public final class ProjectCreationController {
    private final ProjectCreationApplicationService creator;
    private final IdentityContext identity;

    public ProjectCreationController(ProjectCreationApplicationService creator, IdentityContext identity) {
        this.creator = creator;
        this.identity = identity;
    }

    @PostMapping("/new")
    public ResponseEntity<ProjectRegistrationResponse> create(@Valid @RequestBody CreateProjectRequest request,
                                                              HttpServletRequest httpRequest) {
        var ownerId = identity.ownerId(httpRequest);
        requireLocalRequest(httpRequest);
        var result = creator.create(ownerId, request.name(),
                request.canonicalPath(), request.verificationCommands());
        var project = result.project();
        HttpStatus status = result.reopened() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(new ProjectRegistrationResponse(
                project.id(), project.name(), project.canonicalPath().toString(),
                project.verificationCommands(), project.createdAt(), result.reopened()));
    }

    private void requireLocalRequest(HttpServletRequest request) {
        try {
            String remoteAddress = request.getRemoteAddr();
            if (remoteAddress == null || remoteAddress.isBlank()
                    || !InetAddress.getByName(remoteAddress).isLoopbackAddress()) {
                throw new ForbiddenException("New local projects can only be created from this machine");
            }
        } catch (UnknownHostException error) {
            throw new ForbiddenException("Unable to confirm a local project creation request");
        }
    }
}
