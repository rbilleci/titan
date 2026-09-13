CREATE OR REPLACE PROCEDURE "billing"."compute_account_fee"(p_account_id BIGINT, p_gold_rate NUMERIC(38,10), p_std_rate NUMERIC(38,10))
LANGUAGE plpgsql
SECURITY INVOKER
AS $$
DECLARE
    v_balance NUMERIC(38,10);
    v_tier TEXT;
    v_fee NUMERIC(38,10);
BEGIN
    DECLARE
        titan_row_count INTEGER;
    BEGIN
        EXECUTE 'SELECT balance, tier FROM accounts WHERE id = $1' INTO v_balance, v_tier USING p_account_id;
        GET DIAGNOSTICS titan_row_count = ROW_COUNT;
        IF titan_row_count = 0 THEN
            RAISE EXCEPTION USING ERRCODE = '55000', MESSAGE = 'account not found';
        END IF;
    END;
    v_fee := CASE WHEN COALESCE(CASE WHEN v_tier IS NULL THEN FALSE ELSE ('GOLD' = v_tier) END, FALSE) THEN (v_balance * p_gold_rate) ELSE (v_balance * p_std_rate) END;
    EXECUTE 'UPDATE accounts SET fee = $1 WHERE id = $2' USING v_fee, p_account_id;
END;
$$;
