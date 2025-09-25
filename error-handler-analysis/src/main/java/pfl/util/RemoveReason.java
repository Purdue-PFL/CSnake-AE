package pfl.util;

public class RemoveReason 
{
    public String reason;
    public boolean shouldRemove;

    public RemoveReason(boolean shouldRemove, String reason)
    {
        this.reason = reason;
        this.shouldRemove = shouldRemove;
    }

    @Override
    public String toString() 
    {
        return "RemoveReason{" +
               "reason='" + reason + '\'' +
               ", shouldRemove=" + shouldRemove +
               '}';
    }    
}
