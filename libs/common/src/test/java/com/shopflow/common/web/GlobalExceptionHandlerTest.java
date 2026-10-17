package com.shopflow.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RFC 7807 mapping: the status codes clients rely on. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void domainExceptionsMapToTheirStatus() {
        assertEquals(404, handler.handleNotFound(new NotFoundException("x")).getStatus());
        assertEquals(409, handler.handleConflict(new ConflictException("x")).getStatus());
        assertEquals(400, handler.handleBadRequest(new BadRequestException("x")).getStatus());
        assertEquals(409, handler.handleIllegalState(new IllegalStateException("bad transition")).getStatus());
    }

    @Test
    void writeRacesAreConflictsNotServerErrors() {
        assertEquals(409, handler.handleRace(new DataIntegrityViolationException("dup")).getStatus());
        assertEquals(409, handler.handleRace(
                new ObjectOptimisticLockingFailureException("Stock", 1L)).getStatus());
    }

    @Test
    void unexpectedErrorsHideDetails() {
        ProblemDetail pd = handler.handleUnexpected(new RuntimeException("secret stack"));
        assertEquals(500, pd.getStatus());
        assertEquals("Unexpected error", pd.getDetail());
    }
}
