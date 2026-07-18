package dev.abu.screener_backend.user.dto;

import java.util.List;

/**
 * One page of the ADMIN user listing. A stable, explicit envelope (rather than serializing Spring
 * Data's {@code Page}, whose JSON shape is unstable across Boot versions).
 *
 * @param users         the users on this page (newest first)
 * @param page          zero-based page index
 * @param size          page size actually applied
 * @param totalElements total users across all pages
 * @param totalPages    total number of pages
 */
public record AdminUserPage(
        List<AdminUserView> users,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
