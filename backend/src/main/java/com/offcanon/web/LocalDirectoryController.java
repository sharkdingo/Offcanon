package com.offcanon.web;

import com.offcanon.application.LocalDirectoryApplicationService;
import com.offcanon.identity.web.IdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import com.offcanon.shared.web.ForbiddenException;

import static com.offcanon.web.ApiDtos.DirectoryBrowseResponse;
import static com.offcanon.web.ApiDtos.DirectoryEntryResponse;
import static com.offcanon.web.ApiDtos.DirectoryLocationResponse;

@RestController
@RequestMapping("/api/local-directories")
public class LocalDirectoryController {
    private final LocalDirectoryApplicationService directories;
    private final IdentityContext identity;

    public LocalDirectoryController(LocalDirectoryApplicationService directories, IdentityContext identity) {
        this.directories = directories;
        this.identity = identity;
    }

    @GetMapping
    public DirectoryBrowseResponse browse(@RequestParam(required = false) String path,
                                          HttpServletRequest request) {
        identity.requireUser(request);
        requireLocalRequest(request);
        var listing = directories.browse(path);
        return new DirectoryBrowseResponse(
                listing.path().toString(),
                listing.parent() == null ? null : listing.parent().toString(),
                listing.entries().stream()
                        .map(entry -> new DirectoryEntryResponse(entry.name(), entry.path().toString()))
                        .toList(),
                listing.truncated(),
                listing.gitRoot() == null ? null : listing.gitRoot().toString(),
                listing.suggestedName(),
                listing.suggestedVerificationCommands(),
                listing.locations().stream()
                        .map(location -> new DirectoryLocationResponse(location.kind(), location.path().toString()))
                        .toList());
    }

    private void requireLocalRequest(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new ForbiddenException("Local directory browsing is available only on this machine");
            }
        } catch (UnknownHostException error) {
            throw new ForbiddenException("Unable to confirm a local directory browsing request");
        }
    }
}
