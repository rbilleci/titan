CREATE OR REPLACE FUNCTION "billing"."create_invoice"(p_customer_id BIGINT, p_amount NUMERIC(38,10))
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    __titan_genkey1 BIGINT;
BEGIN
    EXECUTE 'INSERT INTO invoices (customer_id, amount) VALUES ($1, $2) RETURNING "id"' INTO __titan_genkey1 USING p_customer_id, p_amount;
    RETURN __titan_genkey1;
END;
$$;
