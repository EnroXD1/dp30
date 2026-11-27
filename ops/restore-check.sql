SELECT
    (SELECT count(*) FROM requests) AS requests,
    (SELECT count(*) FROM assignments) AS assignments,
    (SELECT count(*) FROM request_events) AS request_events,
    (SELECT count(*) FROM assignments a LEFT JOIN requests r ON r.id=a.request_id
      WHERE r.id IS NULL) AS orphan_assignments,
    (SELECT count(*) FROM request_events e LEFT JOIN requests r ON r.id=e.request_id
      WHERE r.id IS NULL) AS orphan_events;
