package com.practicebank.pipeline.master;

import com.practicebank.pipeline.core.IoFailureException;
import com.practicebank.pipeline.master.dto.Branch;
import com.practicebank.pipeline.master.dto.BusinessCalendarDay;
import com.practicebank.pipeline.master.dto.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;

/**
 * REST/JSON client for the Master Reference App (subsystems 01-09).
 *
 * <p>404 is mapped to {@link Optional#empty()} (master record absent); any other
 * non-2xx or transport error is raised as {@link IoFailureException} (status 12).
 */
@Component
public class MasterReferenceClient {

    private static final Logger log = LoggerFactory.getLogger(MasterReferenceClient.class);

    private final RestClient restClient;

    public MasterReferenceClient(RestClient masterRestClient) {
        this.restClient = masterRestClient;
    }

    /** GET /business-calendar/{date}. */
    public Optional<BusinessCalendarDay> getBusinessCalendar(LocalDate date) {
        return get("/business-calendar/{date}", BusinessCalendarDay.class, date.toString());
    }

    /** GET /branches/{branchCode}. */
    public Optional<Branch> getBranch(String branchCode) {
        return get("/branches/{branchCode}", Branch.class, branchCode);
    }

    /** GET /products/{productCode}. */
    public Optional<Product> getProduct(String productCode) {
        return get("/products/{productCode}", Product.class, productCode);
    }

    private <T> Optional<T> get(String uri, Class<T> type, Object... vars) {
        try {
            T body = restClient.get()
                    .uri(uri, vars)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        // Swallowed below by re-checking; throw to interrupt for non-404.
                        if (response.getStatusCode().value() != 404) {
                            throw new IoFailureException(
                                    "master returned " + response.getStatusCode() + " for " + uri);
                        }
                        throw new NotFound();
                    })
                    .body(type);
            return Optional.ofNullable(body);
        } catch (NotFound nf) {
            return Optional.empty();
        } catch (IoFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("master call failed: {}", uri, e);
            throw new IoFailureException("master call failed: " + uri + " (" + e.getMessage() + ")");
        }
    }

    /** Internal signal for HTTP 404. */
    private static final class NotFound extends RuntimeException {
        NotFound() {
            super(null, null, false, false);
        }
    }
}
