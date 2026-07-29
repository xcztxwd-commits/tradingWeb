CREATE FUNCTION content.reject_legacy_message_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'legacy content.messages is read-only'
        USING ERRCODE = '55000';
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_content_messages_read_only
BEFORE INSERT OR UPDATE OR DELETE ON content.messages
FOR EACH ROW
EXECUTE FUNCTION content.reject_legacy_message_mutation();
