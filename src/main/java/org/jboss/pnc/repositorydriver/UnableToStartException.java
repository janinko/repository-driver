package org.jboss.pnc.repositorydriver;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class UnableToStartException extends Throwable {

    public UnableToStartException(String messages) {
        super(messages);
    }
}
