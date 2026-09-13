CREATE OR REPLACE PROCEDURE "test"."surface_query"()
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
BEGIN
    PERFORM "e"."name", CASE WHEN ("m"."score" >= 90) THEN 'top' WHEN ("m"."score" >= 50) THEN 'mid' ELSE 'low' END, "m"."score" FROM "test"."employees" AS "e" JOIN "test"."employees" AS "m" ON ("e"."manager_id" = "m"."id") WHERE "e"."id" NOT IN (5, 6) ORDER BY "m"."score" DESC NULLS LAST, "e"."name" ASC;
END;
$$;
