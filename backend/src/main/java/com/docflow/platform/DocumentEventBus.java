package com.docflow.platform;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class DocumentEventBus {

  private final ApplicationEventPublisher publisher;

  public DocumentEventBus(ApplicationEventPublisher publisher) {
    this.publisher = publisher;
  }

  public void publish(DocumentEvent event) {
    publisher.publishEvent(event);
  }
}
